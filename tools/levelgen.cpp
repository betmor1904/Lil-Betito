// Level generator + solver for Turtle Shot (bank-shot edition).
//
// It mirrors the game's physics (Game.kt: update / collideTurtle / collideShell) in design units
// (1 unit = 1 dp), searches for levels that REQUIRE bank shots, and prints them as Kotlin data
// (Levels.kt). Build:  g++ -O2 -o levelgen levelgen.cpp   Run: ./levelgen <firstLevel> <lastLevel> > out.txt
//
// Keep the constants below in sync with Game.kt.
#include <bits/stdc++.h>
using namespace std;
typedef float F;

// ---- physics (must match Game.kt) ----
static const F R = 13, SR = 15, WT = 14;
static const F LAUNCH = 12.0f, DRAG = 0.02f, BK = 0.92f, SK = 0.95f, SHB = 1.35f, SHS = 1.0f;
static const F MAXV = 17.5f, READY = 0.9f, SETTLE = 0.82f;
// ---- design space ----
static const F DW = 840, PH = 300, EXITX = 822;

struct Rect { F l, t, r, b; int kind; };   // kind 0 = column wall, 1 = rock, 2 = border/exit (not exported)
struct World {
    vector<Rect> w;
    vector<pair<F, F>> sh;
    F exitT, exitB;
};

struct Res { bool win; F x, y; int bounces; };

static inline void collideRect(const Rect& q, F& x, F& y, F& vx, F& vy, int& b) {
    F qx = min(max(x, q.l), q.r), qy = min(max(y, q.t), q.b);
    F dx = x - qx, dy = y - qy, d2 = dx * dx + dy * dy;
    if (d2 >= R * R) return;
    F nx = 0, ny = -1;
    if (d2 > 0.0001f) { F dist = sqrtf(d2); nx = dx / dist; ny = dy / dist; x += nx * (R - dist); y += ny * (R - dist); }
    else y = q.t - R;
    F vn = vx * nx + vy * ny;
    if (vn < 0) {
        F vtx = vx - vn * nx, vty = vy - vn * ny;
        vx = -vn * BK * nx + vtx * SK; vy = -vn * BK * ny + vty * SK;
        if (-vn > 1.2f) b++;
    }
}
static inline void collideShell(F sx, F sy, F& x, F& y, F& vx, F& vy, int& b) {
    F dx = x - sx, dy = y - sy, dist = sqrtf(dx * dx + dy * dy), minD = R + SR;
    if (dist >= minD || dist < 0.0001f) return;
    F nx = dx / dist, ny = dy / dist;
    x += nx * (minD - dist); y += ny * (minD - dist);
    F vn = vx * nx + vy * ny;
    if (vn < 0) {
        F vtx = vx - vn * nx, vty = vy - vn * ny;
        vx = -vn * SHB * nx + vtx * SHS; vy = -vn * SHB * ny + vty * SHS;
        if (-vn > 1.0f) b++;
    }
}

// One shot from rest at (x,y). Returns where he stops (or that he won).
static Res sim(const World& wd, F x, F y, F ang, F pw) {
    F vx = cosf(ang) * LAUNCH * pw, vy = sinf(ang) * LAUNCH * pw;
    int b = 0, settle = -1;
    for (int f = 0; f < 900; f++) {
        F sp = sqrtf(vx * vx + vy * vy);
        if (settle < 0 && f > 0 && sp < READY) settle = 0;
        if (settle >= 0 && ++settle > 40) break;
        for (int s = 0; s < 3; s++) {
            F hdt = 1.f / 3.f, k = 1 - DRAG * hdt;
            vx *= k; vy *= k;
            F sp0 = sqrtf(vx * vx + vy * vy);
            if (sp0 > MAXV) { vx = vx / sp0 * MAXV; vy = vy / sp0 * MAXV; }
            x += vx * hdt; y += vy * hdt;
            if (x < R) { x = R; if (-vx > 1.2f) b++; vx = fabsf(vx) * BK; }
            for (const Rect& q : wd.w) collideRect(q, x, y, vx, vy, b);
            for (auto& s2 : wd.sh) collideShell(s2.first, s2.second, x, y, vx, vy, b);
            if (x > EXITX + WT * 0.5f && y > wd.exitT && y < wd.exitB) return {true, x, y, b};
        }
        if (settle >= 0) { vx *= SETTLE; vy *= SETTLE; }
    }
    return {false, x, y, b};
}

struct Node { F x, y; int d, parent; F ang, pw; };

static F ROB_A = 0.7f, ROB_P = 0.02f, ROB_TOL = 34.f;
static const F DEG = 3.14159265f / 180.f;

// Breadth-first search over rest positions. Returns the minimum number of shots (or 99).
// robust: a shot only counts if small aim errors (+-1.2 deg, +-3% power) still land nearby / still win.
static int solve(const World& wd, F sx, F sy, int maxDepth, int maxBounce, bool robust, F angStep,
                 const vector<F>& powers, vector<Node>* out = nullptr) {
    vector<Node> nodes;
    nodes.push_back({sx, sy, 0, -1, 0, 0});
    unordered_set<int> seen;
    auto key = [](F x, F y) { return (int(x / 18)) * 1000 + int(y / 18); };
    seen.insert(key(sx, sy));
    size_t head = 0;
    while (head < nodes.size()) {
        Node cur = nodes[head];
        int ci = (int)head++;
        if (cur.d >= maxDepth) continue;
        for (F a = 0; a < 360; a += angStep) {
            for (F pw : powers) {
                Res r0 = sim(wd, cur.x, cur.y, a * DEG, pw);
                if (r0.bounces > maxBounce) continue;
                if (!r0.win && seen.count(key(r0.x, r0.y))) continue;
                bool ok = true;
                if (robust) {
                    const F da[4] = {-ROB_A, ROB_A, 0, 0}, dp[4] = {1, 1, 1 - ROB_P, 1 + ROB_P};
                    for (int k = 0; k < 4 && ok; k++) {
                        Res r1 = sim(wd, cur.x, cur.y, (a + da[k]) * DEG, min(1.0f, pw * dp[k]));
                        if (r0.win) { if (!r1.win) ok = false; }
                        else if (r1.win || hypotf(r1.x - r0.x, r1.y - r0.y) > ROB_TOL) ok = false;
                    }
                }
                if (!ok) continue;
                if (r0.win) {
                    if (out) {
                        out->clear();
                        out->push_back({r0.x, r0.y, cur.d + 1, ci, a, pw});
                        int p = ci;
                        while (p > 0) { out->push_back(nodes[p]); p = nodes[p].parent; }
                        reverse(out->begin(), out->end());
                    }
                    return cur.d + 1;
                }
                seen.insert(key(r0.x, r0.y));
                nodes.push_back({r0.x, r0.y, cur.d + 1, ci, a, pw});
            }
        }
    }
    return 99;
}

// ---------------- generator ----------------
static mt19937 rng;
static F rnd(F a, F b) { return a + (b - a) * (rng() / (F)rng.max()); }
static F lerpc(F a, F b, F t) { return a + (b - a) * min(1.f, max(0.f, t)); }

struct Layout {
    World wd;
    F startY;
    int cols;
};

static Layout gen(int L) {
    Layout ly;
    World& wd = ly.wd;
    int n = min(5, 2 + (L - 1) / 4);
    ly.cols = n;
    F t = (L - 1) / 24.f;
    F gapH = lerpc(124, 84, t);
    F exitH = lerpc(104, 72, t);
    // borders
    wd.w.push_back({0, -14, DW + 60, 0, 2});
    wd.w.push_back({0, PH, DW + 60, PH + 14, 2});
    F ec = rnd(exitH / 2 + 12, PH - exitH / 2 - 12);
    wd.exitT = ec - exitH / 2; wd.exitB = ec + exitH / 2;
    wd.w.push_back({EXITX, 0, DW + 60, wd.exitT, 2});
    wd.w.push_back({EXITX, wd.exitB, DW + 60, PH, 2});
    // columns: lane widths vary (tight lanes make the best bank/shell corners); gaps zigzag top/bottom
    vector<F> cx(n), gc(n), lw(n);
    F tot = 0;
    for (int i = 0; i < n; i++) { lw[i] = rnd(70, 250); tot += lw[i]; }
    F span = EXITX - 130 - 190;                 // from the first lane to the last column
    F acc = 190;
    bool topFirst = rng() % 2;
    for (int i = 0; i < n; i++) {
        cx[i] = acc;
        acc += (n == 1 ? 0 : lw[i] / tot * span);
        bool top = ((i % 2 == 0) == topFirst);
        F lo = top ? gapH / 2 + 8 : PH * 0.62f, hi = top ? PH * 0.38f : PH - gapH / 2 - 8;
        gc[i] = rnd(min(lo, hi - 1), hi);
        wd.w.push_back({cx[i], 0, cx[i] + WT, gc[i] - gapH / 2, 0});
        wd.w.push_back({cx[i], gc[i] + gapH / 2, cx[i] + WT, PH, 0});
    }
    if (n > 1) {   // the last column sits well before the exit wall
        F shift = (EXITX - 130) - cx[n - 1];
        (void)shift;
    }
    // rocks hanging from the ceiling / rising from the floor, in the lanes: these make the corners
    int m = min(1 + L / 3, 8);
    for (int k = 0; k < m; k++) {
        int lane = rng() % (n + 1);
        F ll = lane == 0 ? 70 : cx[lane - 1] + WT, rr = lane == n ? EXITX - 20 : cx[lane];
        F w = rnd(26, 40);
        if (rr - ll < w + 60) continue;
        F rx = rnd(ll + 30, rr - w - 30);
        F len = rnd(70, 150);
        bool top = rng() % 2;
        if (top) wd.w.push_back({rx, 0, rx + w, len, 1});
        else wd.w.push_back({rx, PH - len, rx + w, PH, 1});
    }
    // a floating block now and then (a bank target in open water)
    if (L >= 6) {
        int q = 1 + (L >= 14);
        for (int k = 0; k < q; k++) {
            int lane = rng() % (n + 1);
            F ll = lane == 0 ? 80 : cx[lane - 1] + WT, rr = lane == n ? EXITX - 20 : cx[lane];
            F w = 30;
            if (rr - ll < w + 80) continue;
            F rx = rnd(ll + 36, rr - w - 36), ry = rnd(60, PH - 60 - w);
            wd.w.push_back({rx, ry, rx + w, ry + w, 1});
        }
    }
    ly.startY = rnd(50, PH - 50);
    return ly;
}

static bool startClear(const World& wd, F sx, F sy) {
    for (auto& q : wd.w) {
        F qx = min(max(sx, q.l), q.r), qy = min(max(sy, q.t), q.b);
        if ((sx - qx) * (sx - qx) + (sy - qy) * (sy - qy) < (R + 8) * (R + 8)) return false;
    }
    return true;
}


// Where a shell fired from the start would come to rest (mirrors Game.shellPath).
static bool shellHits(const World& wd, F x, F y) {
    if (x < SR || y < SR || y > PH - SR) return true;
    for (const Rect& q : wd.w) {
        F qx = min(max(x, q.l), q.r), qy = min(max(y, q.t), q.b);
        if ((x - qx) * (x - qx) + (y - qy) * (y - qy) < SR * SR) return true;
    }
    return false;
}
static vector<pair<F, F>> shellSpots(const World& wd, F sx, F sy) {
    vector<pair<F, F>> out;
    set<int> seen;
    for (F a = 0; a < 360; a += 2)
        for (F pw : {0.25f, 0.5f, 0.75f, 1.0f}) {
            F dx = cosf(a * DEG), dy = sinf(a * DEG);
            F x = sx + dx * (R + SR + 3), y = sy + dy * (R + SR + 3);
            if (shellHits(wd, x, y)) continue;
            F vx = dx * LAUNCH * pw, vy = dy * LAUNCH * pw;
            for (int i = 0; i < 900; i++) {
                F nx = x + vx / 3, ny = y + vy / 3;
                if (shellHits(wd, nx, ny)) break;
                x = nx; y = ny;
                F k = 1 - DRAG / 3; vx *= k; vy *= k;
                if (sqrtf(vx * vx + vy * vy) < 0.15f) break;
            }
            int key = int(x / 22) * 1000 + int(y / 22);
            if (seen.insert(key).second) out.push_back({x, y});
        }
    return out;
}

static vector<F> P_COARSE = {0.45f, 0.72f, 1.0f};
static vector<F> P_FINE = {0.3f, 0.45f, 0.6f, 0.75f, 0.9f, 1.0f};

// Best shot count with ONE shell placed anywhere in open water (searches only up to maxDepth shots).
static int shellSolve(const Layout& ly, F sx, F sy, int maxDepth, int maxB, int samples) {
    vector<pair<F, F>> cells;
    for (F x = 70; x < EXITX - 20; x += 36)
        for (F y = 24; y < PH - 20; y += 36) {
            if (shellHits(ly.wd, x, y)) continue;
            if (hypotf(x - sx, y - sy) < R + SR + 14) continue;
            cells.push_back({x, y});
        }
    shuffle(cells.begin(), cells.end(), rng);
    int best = 99;
    for (int i = 0; i < (int)cells.size() && i < samples; i++) {
        World w2 = ly.wd;
        w2.sh.push_back(cells[i]);
        int s = solve(w2, sx, sy, min(maxDepth, best - 1), maxB, true, 6.f, P_COARSE);
        if (s < best) best = s;
        if (best <= maxDepth) break;   // found one that saves a shot
    }
    return best;
}

int main(int argc, char** argv) {
    int L0 = atoi(argv[1]), L1 = atoi(argv[2]);
    int seedBase = argc > 3 ? atoi(argv[3]) : 1;
    for (int L = L0; L <= L1; L++) {
        rng.seed(seedBase * 1000 + L);
        int maxB = L <= 8 ? 1 : 2;
        int tries = 0;
        while (true) {
            tries++;
            Layout ly = gen(L);
            F sx = 48, sy = ly.startY;
            if (!startClear(ly.wd, sx, sy)) { if (getenv("V")) fprintf(stderr,"x start\n"); continue; }
            int maxD = ly.cols + 2;
            int s0 = solve(ly.wd, sx, sy, maxD, maxB, true, 6.f, P_COARSE);
            if (getenv("V")) fprintf(stderr, "  try%d s0=%d\n", tries, s0);
            if (s0 > ly.cols + 1 || s0 < 2) continue;
            int sd = solve(ly.wd, sx, sy, maxD, 0, true, 6.f, P_COARSE);
            if (getenv("V")) fprintf(stderr, "    sd=%d\n", sd);
            if (L >= 2 && sd <= s0) continue;
            // shells must matter: one shell placed well has to save a shot
            int s1 = 99;
            if (L >= 2 && tries <= 30) {
                s1 = shellSolve(ly, sx, sy, s0 - 1, maxB, 60);
                if (getenv("V")) fprintf(stderr, "      s1=%d\n", s1);
                if (s1 > s0 - 1) continue;
            }
            vector<Node> path;
            int sf = solve(ly.wd, sx, sy, s0, maxB, true, 3.f, P_FINE, &path);
            if (sf > s0) sf = s0;
            int best = min(sf, s1);
            int par = best + 1;
            int shells = L < 8 ? 3 : (L < 16 ? 4 : 5);
            fprintf(stderr, "L%d tries=%d%s s0=%d sd=%d s1shell=%d sf=%d par=%d\n", L, tries, (L >= 2 && tries > 30) ? " (no shell gate)" : "", s0, sd, s1, sf, par);
            printf("    Lvl(%.1ff, %.1ff, %.1ff, %d, %d, floatArrayOf(", sy, ly.wd.exitT, ly.wd.exitB, par, shells);
            bool first = true;
            for (auto& q : ly.wd.w) {
                if (q.kind == 2) continue;
                printf("%s%.0ff, %.0ff, %.0ff, %.0ff, %d.0f", first ? "" : ", ", q.l, q.t, q.r, q.b, q.kind);
                first = false;
            }
            printf(")),\n");
            fflush(stdout);
            break;
        }
    }
    return 0;
}
