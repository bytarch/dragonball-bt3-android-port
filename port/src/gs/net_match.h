/* Matchmaking with room codes (net_match.c), as the lobby in net.c uses it. */
#ifndef NET_MATCH_H
#define NET_MATCH_H

#define MATCH_ADDRS 4

typedef struct MatchStatus {
    int state;                      /* 0 idle, 1 working, 2 the room is made (code), 3 the other player is known, -1 failed */
    char code[8];
    char error[96];                 /* for the window, when state is -1 */
    char relayWhy[128];             /* why the service gave no relay address ("" when it gave one), for the window */
    char peerName[20];
    char peerAddr[MATCH_ADDRS][64]; /* "ip:port", best first */
    int peerCount;
    int waiting;                    /* quick match only, while waiting: how many players are ahead of this one */
    int queued;                     /* quick match only: how many are waiting altogether, of this build */
    int role;                       /* quick match only: the side of the pair, 1 host 2 joiner (0 until it is known) */
    int wins, losses;               /* the other player's record, or -1 each when they have not asked to show it */
    char peerPlatform[8];           /* "pc" or "mobile", beside their name in the match ("" when not said) */
} MatchStatus;

void Match_Begin(int role, int sock, int localPort, const char *code, const char *name, int version);
/* role 1 hosts a room (and `code` is the one it was given, or "" to ask for a new one), role 2 joins the room
   `code`, role 3 is quick match: the service pairs this player with whoever is waiting, and the side it takes
   follows from the pair. */
void Match_Poll(MatchStatus *out);
void Match_Cancel(void);
/* Says how the match went, for the record. The room it was found in is remembered, so the caller only has to say
   whether this side won. Ignored when there was no room, and saying the same match twice is one match: the service
   counts a code once. */
void Match_Report(int won);
/* Asks the service what this copy's record is, the first time something shows it, on its own thread: the window
   may be opened with no match in sight, and "not known yet" with nothing to wait for is not an answer. */
void Match_AskRecord(const char *name);
void Match_RecordIdentityChanged(void);

#endif
