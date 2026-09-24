/*
 * bfrelay.c - minimal SNI-to-CONNECT transparent relay for BiliFiddler
 * Accepts raw TLS (from iptables REDIRECT), parses ClientHello SNI,
 * sends CONNECT <sni>:443 to upstream HTTP proxy (Fiddler), splices both ways.
 * No external deps. Listens 127.0.0.1:18987 by default.
 * Config file /data/local/tmp/bilifiddler.conf:
 *   proxy <ip> <port>
 *   listen <port>
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <strings.h>
#include <errno.h>
#include <android/log.h>

#define CONF "/data/local/tmp/bilifiddler.conf"
#define TAG  "bfrelay"

static char PROXY_HOST[256] = "10.0.0.230";
static int  PROXY_PORT = 8888;
static int  LISTEN_PORT = 18987;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static void load_conf(void) {
    FILE *f = fopen(CONF, "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        size_t l = strlen(p);
        while (l && (p[l-1] == '\n' || p[l-1] == '\r')) p[--l] = 0;
        if (!strncmp(p, "proxy ", 6)) {
            char host[256]; int port;
            if (sscanf(p + 6, "%255s %d", host, &port) == 2) {
                snprintf(PROXY_HOST, sizeof(PROXY_HOST), "%s", host);
                PROXY_PORT = port;
            }
        } else if (!strncmp(p, "listen ", 7)) {
            int port; if (sscanf(p + 7, "%d", &port) == 1) LISTEN_PORT = port;
        }
    }
    fclose(f);
}

static int recv_record(int fd, unsigned char **out) {
    unsigned char hdr[5];
    size_t got = 0;
    while (got < 5) {
        ssize_t n = recv(fd, hdr + got, 5 - got, 0);
        if (n <= 0) return -1;
        got += n;
    }
    if (hdr[0] != 0x16) return -2;
    size_t len = ((size_t)hdr[3] << 8) | hdr[4];
    if (len == 0 || len > 18000) return -1;
    unsigned char *rec = malloc(5 + len);
    if (!rec) return -1;
    memcpy(rec, hdr, 5);
    size_t have = 0;
    while (have < len) {
        ssize_t n = recv(fd, rec + 5 + have, len - have, 0);
        if (n <= 0) { free(rec); return -1; }
        have += n;
    }
    *out = rec;
    return (int)(5 + len);
}

static int parse_sni(const unsigned char *rec, int reclen, char *sni, size_t sni_len) {
    int p = 5;
    if (reclen < 44 || rec[p] != 0x01) return -1;
    p += 4;
    p += 2 + 32;
    if (p + 1 >= reclen) return -1;
    int sid = rec[p]; p += 1 + sid;
    if (p + 2 > reclen) return -1;
    int cs = (rec[p] << 8) | rec[p+1]; p += 2 + cs;
    if (p + 1 >= reclen) return -1;
    int comp = rec[p]; p += 1 + comp;
    if (p + 2 > reclen) return -1;
    int exts = (rec[p] << 8) | rec[p+1]; p += 2;
    int end = p + exts;
    while (p + 4 <= end && p + 4 <= reclen) {
        int etype = (rec[p] << 8) | rec[p+1];
        int elen  = (rec[p+2] << 8) | rec[p+3];
        p += 4;
        if (p + elen > reclen) break;
        if (etype == 0x0000 && elen >= 5) {
            int nl = (rec[p] << 8) | rec[p+1];
            if (nl >= 3 && rec[p+2] == 0 && p + 5 <= reclen) {
                int name_len = (rec[p+3] << 8) | rec[p+4];
                if (p + 5 + name_len > reclen) name_len = reclen - p - 5;
                size_t cp = (size_t)name_len < sni_len - 1 ? (size_t)name_len : sni_len - 1;
                memcpy(sni, rec + p + 5, cp);
                sni[cp] = 0;
                return 0;
            }
        }
        p += elen;
    }
    return -1;
}

struct pipe_args { int src; int dst; };

static void *pump(void *arg) {
    struct pipe_args *pa = (struct pipe_args *)arg;
    char buf[65536];
    ssize_t n;
    while ((n = recv(pa->src, buf, sizeof(buf), 0)) > 0) {
        if (send(pa->dst, buf, n, MSG_NOSIGNAL) < 0) break;
    }
    shutdown(pa->dst, SHUT_WR);
    free(pa);
    return NULL;
}


/* ---- plain HTTP mode: rewrite first request to absolute-URI, then splice ---- */
static int read_http_head(int fd, char *buf, size_t cap) {
    size_t hb = 0;
    while (hb < cap - 1) {
        char ch;
        ssize_t n = recv(fd, &ch, 1, 0);
        if (n <= 0) return -1;
        buf[hb++] = ch;
        if (hb >= 4 && !memcmp(buf + hb - 4, "\r\n\r\n", 4)) break;
    }
    buf[hb] = 0;
    return (int)hb;
}

static void handle_http(int client) {
    char head[8192];
    if (read_http_head(client, head, sizeof(head)) < 0) { close(client); return; }
    /* parse request line: METHOD SP PATH SP VERSION */
    char method[16], path[2048], ver[16];
    if (sscanf(head, "%15s %2047s %15s", method, path, ver) != 3) { close(client); return; }
    /* find Host header */
    char host[256] = {0};
    char *h = strstr(head, "\r\nHost:");
    if (!h) h = strstr(head, "\r\nhost:");
    if (!h) { close(client); return; }
    h += 5;
    size_t hi = 0;
    while (*h && *h != '\r' && *h != '\n' && *h != ' ' && hi < sizeof(host) - 1) host[hi++] = *h++;
    host[hi] = 0;
    /* build absolute-URI request */
    char abs[10240];
    int al = snprintf(abs, sizeof(abs), "%s http://%s%s %s\r\n", method, host, path, ver);
    /* append remaining original headers after first line */
    char *rest = strstr(head, "\r\n") + 2;
    int rl = (int)strlen(rest);
    if (al + rl >= (int)sizeof(abs)) { close(client); return; }
    memcpy(abs + al, rest, rl);
    al += rl;
    /* drop incoming Proxy-Connection and force close so we never need rewrite later requests */
    /* crude: append Connection: close before final CRLF if not present */
    /* connect to proxy and send */
    int up = socket(AF_INET, SOCK_STREAM, 0);
    if (up < 0) { close(client); return; }
    struct sockaddr_in pa;
    memset(&pa, 0, sizeof(pa));
    pa.sin_family = AF_INET;
    pa.sin_port = htons((unsigned short)PROXY_PORT);
    if (inet_pton(AF_INET, PROXY_HOST, &pa.sin_addr) != 1) { close(client); close(up); return; }
    if (connect(up, (struct sockaddr *)&pa, sizeof(pa)) < 0) { close(client); close(up); return; }
    /* convert to HTTP/1.1? keep original version; Fiddler accepts 1.1/1.0 absolute URI.
       Ensure Connection: close to end session after first response. */
    char *ci = strcasestr(abs, "\r\nConnection:");
    if (ci) {
        char *ce = strstr(ci + 2, "\r\n");
        if (ce) {
            /* replace connection header value with close */
            char rebuilt[10240];
            size_t first = ci - abs;
            memcpy(rebuilt, abs, first);
            int bl = snprintf(rebuilt + first, sizeof(rebuilt) - first, "Connection: close\r\n");
            memcpy(rebuilt + first + bl, ce + 2, strlen(ce + 2) + 1);
            memcpy(abs, rebuilt, sizeof(abs));
            al = strlen(abs);
        }
    } else {
        if (al + 20 < (int)sizeof(abs)) { memcpy(abs + al - 2, "Connection: close\r\n\r\n", 22); al += 20; }
    }
    send(up, abs, (size_t)al, MSG_NOSIGNAL);
    struct pipe_args *a1 = malloc(sizeof(*a1));
    struct pipe_args *a2 = malloc(sizeof(*a2));
    a1->src = client; a1->dst = up;
    a2->src = up;     a2->dst = client;
    pthread_t t1, t2;
    pthread_create(&t1, NULL, pump, a1);
    pthread_create(&t2, NULL, pump, a2);
    pthread_join(t1, NULL);
    pthread_join(t2, NULL);
    close(client); close(up);
}

struct conn_arg { int fd; };



static void *handle(void *arg) {
    struct conn_arg *ca = (struct conn_arg *)arg;
    int client = ca->fd;
    free(ca);

    /* peek first byte to decide TLS vs plain HTTP */
    unsigned char first;
    ssize_t fn = recv(client, &first, 1, MSG_PEEK);
    if (fn <= 0) { close(client); return NULL; }
    if (first != 0x16) { handle_http(client); return NULL; }
    unsigned char *rec = NULL;
    char sni[256] = {0};
    int rlen = recv_record(client, &rec);
    if (rlen <= 0) { close(client); return NULL; }
    if (parse_sni(rec, rlen, sni, sizeof(sni)) != 0) {
        free(rec); close(client); return NULL;
    }
    LOGI("sni %s", sni);

    int up = socket(AF_INET, SOCK_STREAM, 0);
    if (up < 0) { free(rec); close(client); return NULL; }
    int one = 1;
    setsockopt(up, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    struct sockaddr_in pa;
    memset(&pa, 0, sizeof(pa));
    pa.sin_family = AF_INET;
    pa.sin_port = htons((unsigned short)PROXY_PORT);
    if (inet_pton(AF_INET, PROXY_HOST, &pa.sin_addr) != 1) {
        free(rec); close(client); close(up); return NULL;
    }
    if (connect(up, (struct sockaddr *)&pa, sizeof(pa)) < 0) {
        free(rec); close(client); close(up); return NULL;
    }

    char req[512];
    int rl = snprintf(req, sizeof(req),
        "CONNECT %s:443 HTTP/1.1\r\nHost: %s:443\r\n\r\n", sni, sni);
    if (send(up, req, (size_t)rl, MSG_NOSIGNAL) < 0) {
        free(rec); close(client); close(up); return NULL;
    }

    char hdr[1024];
    size_t hb = 0;
    while (hb < sizeof(hdr) - 1) {
        char ch;
        ssize_t n = recv(up, &ch, 1, 0);
        if (n <= 0) break;
        hdr[hb++] = ch;
        if (hb >= 4 && !memcmp(hdr + hb - 4, "\r\n\r\n", 4)) break;
    }
    hdr[hb] = 0;
    if (!strstr(hdr, " 200")) {
        LOGI("proxy reject for %s", sni);
        free(rec); close(client); close(up); return NULL;
    }

    send(up, rec, (size_t)rlen, MSG_NOSIGNAL); /* replay buffered ClientHello */
    free(rec);

    struct pipe_args *a1 = malloc(sizeof(*a1));
    struct pipe_args *a2 = malloc(sizeof(*a2));
    a1->src = client; a1->dst = up;
    a2->src = up;     a2->dst = client;
    pthread_t t1, t2;
    pthread_create(&t1, NULL, pump, a1);
    pthread_create(&t2, NULL, pump, a2);
    pthread_join(t1, NULL);
    pthread_join(t2, NULL);
    close(client);
    close(up);
    return NULL;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    load_conf();
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons((unsigned short)LISTEN_PORT);
    if (bind(srv, (struct sockaddr *)&a, sizeof(a)) < 0) { LOGI("bind fail errno=%d", errno); return 1; }
    if (listen(srv, 128) < 0) return 1;
    LOGI("bfrelay listening :%d -> %s:%d", LISTEN_PORT, PROXY_HOST, PROXY_PORT);
    for (;;) {
        int fd = accept(srv, NULL, NULL);
        if (fd < 0) continue;
        pthread_t t;
        struct conn_arg *ca = malloc(sizeof(*ca));
        if (!ca) { close(fd); continue; }
        ca->fd = fd;
        if (pthread_create(&t, NULL, handle, ca) != 0) {
            free(ca); close(fd); continue;
        }
        pthread_detach(t);
    }
    return 0;
}
