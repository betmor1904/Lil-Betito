"""Casino-style sounds: slot-machine bells, coin cascades, jackpot. Overwrites tick/clink/siren/fanfare
and adds coins/slot/kaching in app/src/main/res/raw.  Run: python3 tools/make_sfx_casino.py (needs numpy)"""
import numpy as np, wave, os
SR = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(21)

def save(name, x, peak=0.97):
    x = np.tanh(1.8 * x) / np.tanh(1.8)            # soft clip = louder, punchier
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

def t(dur): return np.arange(int(dur * SR)) / SR
def lowpass(x, k): return np.convolve(x, np.ones(k) / k, mode="same")

def bell(f, dur=0.6, dec=5.0, amp=1.0):
    n = t(dur)
    x = np.zeros(len(n))
    for i, (p, a) in enumerate([(1, 1.0), (2.76, 0.55), (5.4, 0.32), (8.93, 0.16)]):
        x += a * np.sin(2 * np.pi * f * p * n) * np.exp(-n * dec * (1 + 0.45 * i))
    x[:60] *= np.linspace(0, 1, 60)
    return amp * x

def ping(f, dur=0.2, dec=24.0, amp=1.0):
    n = t(dur)
    x = np.sin(2 * np.pi * f * n) + 0.5 * np.sin(2 * np.pi * f * 2.4 * n)
    x = x * np.exp(-n * dec)
    x[:30] *= np.linspace(0, 1, 30)
    return amp * x

def mix(total, parts):
    buf = np.zeros(int(total * SR))
    for start, sig in parts:
        i = int(start * SR)
        end = min(len(buf), i + len(sig))
        if end > i: buf[i:end] += sig[:end - i]
    return buf

def coins(count, span, start=0.0):
    return [(start + rng.random() * span, ping(rng.uniform(2300, 4300), amp=rng.uniform(0.4, 1.0))) for _ in range(count)]

# reel tick for plain walls and the floor: a crisp ratchet click
n = t(0.045)
click = lowpass(rng.standard_normal(len(n)), 3) * np.exp(-n * 160) + 0.7 * np.sin(2 * np.pi * 1500 * n) * np.exp(-n * 110)
save("tick", click)

# obstacle: chip clink + bright bell
save("clink", mix(0.5, [(0, bell(1760, 0.45, 9)), (0.0, ping(3300, 0.25, 18, 0.9)), (0.03, ping(4100, 0.2, 22, 0.6))]))

# combo 3-4: coins pouring into the tray
save("coins", mix(0.95, coins(16, 0.7)))

# combo 5-7: slot "ding ding ding"
save("slot", mix(1.5, [(0.0, bell(1318.5, 1.0, 3.5)), (0.14, bell(1568, 1.0, 3.5)), (0.28, bell(2093, 1.2, 3.0)), (0.28, ping(4200, 0.3, 14, 0.6))]))

# combo 8+: jackpot - alarm bell ringing, coin shower, rising siren underneath
n = t(1.9)
sweep = np.sin(np.cumsum(500 + 900 * (n / 1.9)) / SR * 2 * np.pi) * 0.25
rings = [(0.07 * i, bell(1800 if i % 2 == 0 else 2250, 0.28, 12, 0.9)) for i in range(26)]
save("siren", mix(1.9, rings + coins(34, 1.5, 0.1)) + sweep)

# hole entry: slot-machine win fanfare
arp = [(0.11 * i, bell(f, 0.7, 5)) for i, f in enumerate([1046.5, 1318.5, 1568, 2093])]
chord = [(0.46, bell(f, 1.2, 3.2, 0.8)) for f in (1046.5, 1318.5, 1568, 2093)]
save("fanfare", mix(1.9, arp + chord + coins(30, 1.2, 0.5)))

# coin brick: cash register "ka-CHING"
drawer = lowpass(rng.standard_normal(int(0.07 * SR)), 10) * np.exp(-t(0.07) * 40)
thud = np.sin(2 * np.pi * 150 * t(0.08)) * np.exp(-t(0.08) * 45)
save("kaching", mix(0.9, [(0, drawer * 1.2), (0, thud), (0.09, bell(1976, 0.8, 6)), (0.09, bell(2637, 0.8, 6, 0.8)), (0.1, ping(4400, 0.3, 14, 0.5))]))
print("done")
