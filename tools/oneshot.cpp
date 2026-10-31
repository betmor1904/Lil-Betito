// ONE-SHOT level generator for Turtle Shot (paddle edition).
//
// Mirrors Game.kt physics (waterDrag, launchSpeed, rock bounce, flat PADDLE mirrors) in design units
// (1 unit = 1 dp). It builds each hole AROUND a solution: pick a shot, place paddles on its path
// (snapped to the 15-degree steps the game allows), then put the column gaps and rocks around that path.
// A hole is kept only if the shot still wins when aim/power are a bit off and the paddles are nudged,
// and if the hole has NO paddle-free solution in two shots (so the setup really matters).
//
// Build: g++ -O2 -o oneshot oneshot.cpp      Run: ./oneshot <firstLevel> <lastLevel> <seed> > out.txt
// Keep the constants in sync with Game.kt.
#include <bits/stdc++.h>
using namespace std;
typedef float F;

static const F R = 13, WT = 14, PAD_HALF = 30, PAD_R = 5;
static const F LAUNCH = 18.0f, DRAG = 0.016f, BK = 0.92f, SK = 0.95f;
static const F MAXV = 21.0f, READY = 0.9f, SETTLE = 0.82f;
static const F DW = 840, PH = 300, EXITX = 822;
static const F DEG = 3.14159265f / 180.f;
static const F SHELL_MAXX = EXITX - 30;

struct Rect { F l, t, r, b; int kind; };   // kind 0 = column wall, 1 = rock, 2 = border/exit (not exported)
struct Pad { F x, y, a; };                  // center and angle (radians) of the flat side
struct World {
    vector<Rect> w;
    vector<Pad> pads;
    F exitT, exitB;
};
struct Res { bool win; F x, y; int bounces; };

static int PADH = 0;
static vector<int> HITS;

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
static inline void padClosest(const Pad& p, F px, F py, F& cx, F& cy) {
    F ax = cosf(p.a) * PAD_HALF, ay = sinf(p.a) * PAD_HALF;
    F bx = p.x - ax, by = p.y - ay, ex = ax * 2, ey = ay * 2;
    F t = ((px - bx) * ex + (py - by) * ey) / (ex * ex + ey * ey);
    t = min(1.f, max(0.f, t));
    cx = bx + ex * t; cy = by + ey * t;
}
static inline void collidePad(const Pad& p, F& x, F& y, F& vx, F& vy, int& b) {
    F qx, qy;
    padClosest(p, x, y, qx, qy);
    F dx = x - qx, dy = y - qy, dist = sqrtf(dx * dx + dy * dy), minD = R + PAD_R;
    if (dist >= minD || dist < 0.0001f) return;
    F nx = dx / dist, ny = dy / dist;
    x += nx * (minD - dist); y += ny * (minD - dist);
    F vn = vx * nx + vy * ny;
    if (vn < 0) {
        vx -= 2 * vn * nx; vy -= 2 * vn * ny;
        if (-vn > 1.0f) { b++; PADH++; }
    }
}

// One shot from rest at (x,y); optionally records the path (one point per sub-step).
static Res sim(const World& wd, F x, F y, F ang, F pw, vector<pair<F, F>>* pts = nullptr) {
    F vx = cosf(ang) * LAUNCH * pw, vy = sinf(ang) * LAUNCH * pw;
    int b = 0, settle = -1;
    if (pts) { pts->clear(); HITS.clear(); }
    PADH = 0;
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
            int before = PADH;
            for (const Pad& p : wd.pads) collidePad(p, x, y, vx, vy, b);
            if (pts) { if (PADH > before) HITS.push_back((int)pts->size()); pts->push_back({x, y}); }
            if (x > EXITX + WT * 0.5f && y > wd.exitT && y < wd.exitB) return {true, x, y, b};
        }
        if (settle >= 0) { vx *= SETTLE; vy *= SETTLE; }
    }
    return {false, x, y, b};
}

// ---- shell-free two-shot solver (to make sure paddles are NEEDED) ----
struct Node { F x, y; int d; };
static int solveNoPads(const World& wd, F sx, F sy, int maxDepth, int maxBounce) {
    vector<Node> nodes;
    nodes.push_back({sx, sy, 0});
    unordered_set<int> seen;
    auto key = [](F x, F y) { return (int(x / 18)) * 1000 + int(y / 18); };
    seen.insert(key(sx, sy));
    size_t head = 0;
    while (head < nodes.size()) {
        Node cur = nodes[head++];
        if (cur.d >= maxDepth) continue;
        for (F a = 0; a < 360; a += 4)
            for (F pw : {0.45f, 0.72f, 1.0f}) {
                Res r0 = sim(wd, cur.x, cur.y, a * DEG, pw);
                if (r0.bounces > maxBounce) continue;
                if (r0.win) return cur.d + 1;
                if (seen.count(key(r0.x, r0.y))) continue;
                seen.insert(key(r0.x, r0.y));
                nodes.push_back({r0.x, r0.y, cur.d + 1});
            }
    }
    return 99;
}

// ---------------- generator ----------------
static mt19937 rng;
static F rnd(F a, F b) { return a + (b - a) * (rng() / (F)rng.max()); }
static F lerpc(F a, F b, F t) { return a + (b - a) * min(1.f, max(0.f, t)); }

static bool startClear(const World& wd, F sx, F sy) {
    for (auto& q : wd.w) {
        F qx = min(max(sx, q.l), q.r), qy = min(max(sy, q.t), q.b);
        if ((sx - qx) * (sx - qx) + (sy - qy) * (sy - qy) < (R + 8) * (R + 8)) return false;
    }
    return true;
}
static F rectDist(const Rect& q, F x, F y) {
    F qx = min(max(x, q.l), q.r), qy = min(max(y, q.t), q.b);
    return hypotf(x - qx, y - qy);
}

// Does a paddle fit here (clear of walls, the start spot and other paddles)? Mirrors Game.shellSpotFree.
static bool padFits(const World& wd, const vector<Pad>& others, int skip, const Pad& p, F sx, F sy) {
    for (int i = -3; i <= 3; i++) {
        F px = p.x + cosf(p.a) * PAD_HALF * i / 3.f, py = p.y + sinf(p.a) * PAD_HALF * i / 3.f;
        if (px > SHELL_MAXX || px < PAD_R + 2) return false;
        for (auto& q : wd.w) if (rectDist(q, px, py) < PAD_R + 2) return false;
        for (int k = 0; k < (int)others.size(); k++) {
            if (k == skip) continue;
            F cx, cy; padClosest(others[k], px, py, cx, cy);
            if (hypotf(px - cx, py - cy) < PAD_R * 2 + 4) return false;
        }
    }
    F cx, cy; padClosest(p, sx, sy, cx, cy);
    return hypotf(sx - cx, sy - cy) >= R + PAD_R + 3;
}

static bool winsAt(const World& w, F sx, F sy, F a, F pw, int maxB, int needPad) {
    Res r = sim(w, sx, sy, a * DEG, pw);
    return r.win && r.bounces <= maxB && PADH >= needPad;
}

// Make the winning shot robust: it must still win with aim +-0.7 deg, power +-2%, and the paddles nudged.
static bool refine(const World& w, F sx, F sy, F a0, F p0, int maxB, int needPad, F& ao, F& po) {
    F bestScore = -1;
    for (F da = -2; da <= 2.001f; da += 0.25f)
        for (F dp = -0.06f; dp <= 0.0601f; dp += 0.01f) {
            F a = a0 + da, pw = min(1.0f, p0 + dp);
            if (pw < 0.25f) continue;
            if (!winsAt(w, sx, sy, a, pw, maxB, needPad)) continue;
            int ok = 0, tot = 0;
            for (F ea : {-0.7f, 0.7f, 0.f})
                for (F ep : {-0.02f, 0.02f, 0.f}) { tot++; if (winsAt(w, sx, sy, a + ea, min(1.0f, pw * (1 + ep)), maxB, needPad)) ok++; }
            if (ok == tot && ok > bestScore) { bestScore = ok; ao = a; po = pw; }
        }
    if (bestScore < 0) return false;
    World w2 = w;
    for (int t = 0; t < 10; t++) {
        for (size_t i = 0; i < w.pads.size(); i++) {
            w2.pads[i].x = w.pads[i].x + rnd(-4, 4);
            w2.pads[i].y = w.pads[i].y + rnd(-4, 4);
        }
        if (!winsAt(w2, sx, sy, ao, po, maxB + 1, 0)) return false;
    }
    return true;
}

static long REJC[40];
static bool REJ(int i) { REJC[i]++; return false; }

struct Out { World wd; F sy; vector<Pad> pads; F ang, pw; int cols; };

static bool buildLevel(int L, int K, Out& o) {
    const F sx = 48;
    F sy = rnd(50, PH - 50);
    World base;
    base.w.push_back({0, -14, DW + 60, 0, 2});
    base.w.push_back({0, PH, DW + 60, PH + 14, 2});
    base.exitT = 0; base.exitB = PH;
    if (!startClear(base, sx, sy)) return REJ(1);
    F A = rnd(-55, 55), pw = 1.0f;
    vector<pair<F, F>> pts;
    Res r = sim(base, sx, sy, A * DEG, pw, &pts);
    for (int k = 0; k < K; k++) {
        if ((int)HITS.size() < k) return REJ(2);
        int lo = k == 0 ? 0 : HITS[k - 1];
        int hi = (int)pts.size() - 4;
        if (hi - lo < 40) return REJ(3);
        bool last = (k == K - 1);
        bool placed = false;
        vector<pair<F, F>> p2;
        // candidate placements: a point on the path and one of the 12 allowed orientations (multiples of 15 degrees)
        // the last paddle has to finish the job, so try EVERY spot on the path with EVERY orientation (shuffled)
        vector<pair<int, int>> cl;
        if (last) {
            for (int jj = 0; jj < 30; jj++)
                for (int a12 = 0; a12 < 12; a12++) cl.push_back({lo + (int)((hi - lo) * (0.12f + 0.8f * jj / 29.f)), a12});
            shuffle(cl.begin(), cl.end(), rng);
        }
        int tries = last ? (int)cl.size() : 25;
        for (int att = 0; att < tries && !placed; att++) {
            int j = last ? cl[att].first : lo + (int)((hi - lo) * rnd(0.15f, 0.9f));
            Pad cand{pts[j].first, pts[j].second, (F)(last ? cl[att].second : int(rng() % 12)) * 15.f * DEG};
            if (!padFits(base, base.pads, -1, cand, sx, sy)) continue;
            base.pads.push_back(cand);
            Res r2 = sim(base, sx, sy, A * DEG, pw, &p2);
            bool good = (int)HITS.size() >= k + 1;
            if (good && last) good = r2.win && r2.bounces <= 8 && (int)HITS.size() == K;
            if (good && !last) {
                // keep the path alive and make the pad actually turn it
                int after = (int)p2.size() - HITS[k];
                good = after > 120;
            }
            if (good) { pts = p2; r = r2; placed = true; }
            else base.pads.pop_back();
        }
        if (!placed) return REJ(4 + (last ? 1 : 0));
    }
    // power slack: still reaches the exit with 6% less power
    { vector<pair<F, F>> p3; Res r3 = sim(base, sx, sy, A * DEG, pw * 0.97f, &p3); if (!r3.win || (int)HITS.size() != K) return REJ(6); }
    r = sim(base, sx, sy, A * DEG, pw, &pts);
    F exitH = lerpc(104, 72, (L - 1) / 24.f);
    F ec = r.y + rnd(-0.3f, 0.3f) * (exitH / 2 - R - 8);
    F eT = ec - exitH / 2, eB = ec + exitH / 2;
    if (eT < 4 || eB > PH - 4) return REJ(7);

    World w;
    w.w.push_back({0, -14, DW + 60, 0, 2});
    w.w.push_back({0, PH, DW + 60, PH + 14, 2});
    w.w.push_back({EXITX, 0, DW + 60, eT, 2});
    w.w.push_back({EXITX, eB, DW + 60, PH, 2});
    w.exitT = eT; w.exitB = eB;
    w.pads = base.pads;

    // columns: a gap wherever the path crosses each one
    int n = min(5, 2 + (L - 1) / 4);
    F gapH = lerpc(124, 84, (L - 1) / 24.f);
    vector<F> colx;
    int tries = 0;
    while ((int)colx.size() < n && tries++ < 300) {
        F x = rnd(130, EXITX - 100);
        bool ok = true;
        for (F c : colx) if (fabsf(c - x) < 80) ok = false;
        for (auto& p : w.pads) if (fabsf(p.x - (x + WT / 2)) < PAD_HALF + WT / 2 + 12) ok = false;
        if (!ok) continue;
        vector<int> idx;
        F ymin = 1e9, ymax = -1e9;
        for (int j = 0; j < (int)pts.size(); j++)
            if (pts[j].first > x - R && pts[j].first < x + WT + R) { idx.push_back(j); ymin = min(ymin, pts[j].second); ymax = max(ymax, pts[j].second); }
        if (idx.empty() || (int)idx.size() != idx.back() - idx.front() + 1) continue;
        F spread = ymax - ymin;
        if (spread + 2 * R + 12 > gapH) continue;
        F slack = (gapH - spread - 2 * R - 12) / 2 * 0.6f;
        F gc = (ymin + ymax) / 2 + rnd(-slack, slack);
        gc = min(max(gc, gapH / 2 + 4), PH - gapH / 2 - 4);
        if (ymin < gc - gapH / 2 + R + 6 || ymax > gc + gapH / 2 - R - 6) continue;
        colx.push_back(x);
        w.w.push_back({x, 0, x + WT, gc - gapH / 2, 0});
        w.w.push_back({x, gc + gapH / 2, x + WT, PH, 0});
    }
    o.cols = (int)colx.size();
    if (o.cols < min(n, 2)) return REJ(8);

    // rocks and blocks that stay clear of the path and the paddles, so they only matter to other lines
    auto clearOfPath = [&](const Rect& q) {
        for (int j = 0; j < (int)pts.size(); j += 3) if (rectDist(q, pts[j].first, pts[j].second) < R + 16) return false;
        for (auto& p : w.pads)
            for (int i = -3; i <= 3; i++)
                if (rectDist(q, p.x + cosf(p.a) * PAD_HALF * i / 3.f, p.y + sinf(p.a) * PAD_HALF * i / 3.f) < R + 14) return false;
        for (F c : colx) if (q.r > c - 24 && q.l < c + WT + 24) return false;
        if (q.l < 80 || q.r > EXITX - 40) return false;
        return true;
    };
    int m = min(1 + L / 3, 8);
    for (int k = 0, g = 0; k < m && g < 200; g++) {
        F wd2 = rnd(26, 40), len = rnd(60, 150), x = rnd(80, EXITX - 60 - wd2);
        Rect q = (rng() % 2) ? Rect{x, 0, x + wd2, len, 1} : Rect{x, PH - len, x + wd2, PH, 1};
        if (!clearOfPath(q)) continue;
        w.w.push_back(q); k++;
    }
    if (L >= 6) {
        int q2 = 1 + (L >= 14);
        for (int k = 0, g = 0; k < q2 && g < 200; g++) {
            F x = rnd(90, EXITX - 90), y = rnd(50, PH - 80);
            Rect q{x, y, x + 30, y + 30, 1};
            if (!clearOfPath(q)) continue;
            w.w.push_back(q); k++;
        }
    }
    if (!startClear(w, sx, sy)) return REJ(9);
    F ao, po;
    if (!refine(w, sx, sy, A, pw, 8, K, ao, po)) return REJ(10);
    // the hole must NEED paddles: no paddle-free solution in two shots
    if (L >= 2 && getenv("NEED")) {
        World ns = w; ns.pads.clear();
        if (solveNoPads(ns, sx, sy, 2, 3) <= 2) return REJ(11);
    }
    o.wd = w; o.sy = sy; o.pads = w.pads; o.ang = ao; o.pw = po;
    return true;
}

int main(int argc, char** argv) {
    int L0 = atoi(argv[1]), L1 = atoi(argv[2]);
    int seedBase = argc > 3 ? atoi(argv[3]) : 1;
    for (int L = L0; L <= L1; L++) {
        rng.seed(seedBase * 1000 + L);
        int K = L <= 12 ? 2 : 3;
        if (getenv("K")) K = atoi(getenv("K"));
        long tries = 0;
        Out o;
        while (true) {
            tries++;
            if (!buildLevel(L, K, o)) {
                if (tries % 20000 == 0) { fprintf(stderr, "L%d tries=%ld rej:", L, tries); for (int i = 1; i < 30; i++) if (REJC[i]) fprintf(stderr, " %d:%ld", i, REJC[i]); fprintf(stderr, "\n"); }
                continue;
            }
            int give = min(5, K + (L >= 3 ? 1 : 0));
            fprintf(stderr, "L%d tries=%ld paddles=%d give=%d cols=%d aim=%.2f pw=%.2f\n", L, tries, K, give, o.cols, o.ang, o.pw);
            printf("    Lvl(%.1ff, %.1ff, %.1ff, 2, %d, floatArrayOf(", o.sy, o.wd.exitT, o.wd.exitB, give);
            bool first = true;
            for (auto& q : o.wd.w) {
                if (q.kind == 2) continue;
                printf("%s%.0ff, %.0ff, %.0ff, %.0ff, %d.0f", first ? "" : ", ", q.l, q.t, q.r, q.b, q.kind);
                first = false;
            }
            printf("), floatArrayOf(");
            for (auto& p : o.pads) printf("%.0ff, %.0ff, %.3ff, ", p.x, p.y, p.a);
            printf("%.2ff, %.2ff)),\n", o.ang, o.pw);
            fflush(stdout);
            break;
        }
    }
    return 0;
}
