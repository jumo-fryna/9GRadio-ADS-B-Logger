"""UI-independent ADS-B core, derived from Android ProtocolDecoders.kt.

Preserves the 2 MS/s Mode-S PPM/CRC pipeline. Corrects Q altitude bit removal,
signed velocity and globally unambiguous, time-bounded CPR. GPL-3.0-or-later.
"""
from dataclasses import dataclass, asdict
import math
import time
import numpy as np

FREQUENCY = 1_090_000_000
SAMPLE_RATE = 2_000_000


@dataclass
class Frame:
    icao: str
    timestamp: float
    tc: int = 0
    callsign: str | None = None
    altitude: int | None = None
    latitude: float | None = None
    longitude: float | None = None
    speed: float | None = None
    heading: float | None = None
    vertical_rate: int | None = None
    on_ground: bool | None = None
    raw: str = ""


def crc24(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b << 16
        for _ in range(8):
            crc = ((crc << 1) ^ (0xFFF409 if crc & 0x800000 else 0)) & 0xFFFFFF
    return crc


def valid_position(lat, lon):
    return (lat is not None and lon is not None and math.isfinite(lat)
            and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180)


def distance_nm(a, b, c, d):
    h = (math.sin(math.radians(c-a)/2)**2 + math.cos(math.radians(a)) *
         math.cos(math.radians(c)) * math.sin(math.radians(d-b)/2)**2)
    return 6880.13 * math.asin(math.sqrt(max(0, min(1, h))))


def nl(lat):
    if abs(lat) >= 87:
        return 2 if abs(lat) == 87 else 1
    return math.floor(2*math.pi / math.acos(1-(1-math.cos(math.pi/30))/math.cos(math.radians(lat))**2))


class Decoder:
    def __init__(self):
        self.cpr = {}
        self.accepted = 0
        self.rejected = 0

    def decode(self, raw: bytes, timestamp=None):
        now = time.time() if timestamp is None else timestamp
        # Only DF17 / DF18 CF0 (ICAO-addressed ADS-B ES). Other DF18 address
        # namespaces and TIS-B/ADS-R are deliberately not merged into ICAO tracks.
        if len(raw) != 14 or raw[0] >> 3 not in (17, 18):
            return None
        if raw[0] >> 3 == 18 and raw[0] & 7 != 0:
            return None
        if crc24(raw) != 0:
            self.rejected += 1
            return None
        self.accepted += 1
        icao = raw[1:4].hex().upper()
        me = int.from_bytes(raw[4:11], 'big')
        tc = me >> 51
        def bits(start, length):  # ME bit positions are one-based / MSB first
            return (me >> (56-start-length+1)) & ((1 << length)-1)
        f = Frame(icao, now, tc, raw=raw.hex().upper())
        if 1 <= tc <= 4:
            chars = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######"
            f.callsign = ''.join(chars[bits(9+6*i, 6)] for i in range(8)).replace('_', ' ').replace('#', '').strip() or None
        elif 9 <= tc <= 18:
            f.on_ground = False
            alt = bits(9, 12)
            if alt & 16:  # Q=1: remove Q and compress the remaining eleven bits
                f.altitude = (((alt & 0xFE0) >> 1) | (alt & 15))*25-1000
            # Q=0 Gillham is unknown in this MVP; do not invent an altitude.
            odd = bits(22, 1)
            item = (bits(23, 17)/131072, bits(40, 17)/131072, now)
            if len(self.cpr) > 4096:
                self.cpr = {k:v for k,v in self.cpr.items() if now-max(x[2] for x in v.values()) < 60}
                while len(self.cpr)>4096:
                    self.cpr.pop(next(iter(self.cpr)))
            pair = self.cpr.setdefault(icao, {})
            pair[odd] = item
            if 0 in pair and 1 in pair and abs(pair[0][2]-pair[1][2]) <= 10:
                e, o = pair[0], pair[1]
                j = math.floor(59*e[0]-60*o[0]+0.5)
                le = 6*((j % 60)+e[0]); lo = 360/59*((j % 59)+o[0])
                le = le-360 if le >= 270 else le
                lo = lo-360 if lo >= 270 else lo
                if -90 <= le <= 90 and -90 <= lo <= 90 and nl(le) == nl(lo):
                    lat = lo if odd else le
                    n = nl(lat); ni = max(n-odd, 1)
                    m = math.floor(e[1]*(n-1)-o[1]*n+0.5)
                    lon = 360/ni*((m % ni)+(o[1] if odd else e[1]))
                    lon = (lon+180) % 360-180
                    if valid_position(lat, lon):
                        f.latitude, f.longitude = lat, lon
        elif tc == 19:
            subtype = bits(6, 3)
            if subtype in (1, 2):
                ew, ns = bits(15, 10), bits(26, 10)
                if ew and ns:
                    factor = 4 if subtype == 2 else 1
                    vx = (ew-1)*(-1 if bits(14,1) else 1)*factor
                    vy = (ns-1)*(-1 if bits(25,1) else 1)*factor
                    f.speed = math.hypot(vx, vy)
                    f.heading = math.degrees(math.atan2(vx,vy)) % 360
            vr = bits(38,9)
            if vr:
                f.vertical_rate = (vr-1)*64*(-1 if bits(37,1) else 1)
        elif 5 <= tc <= 8:
            f.on_ground = True  # surface CPR not decoded without reference
        return f


class IqDecoder:
    """Vectorized 2 MS/s detector with exact 239-sample carry and odd USB byte carry.

    No CRC repair. Adaptive preamble contrast avoids the Android absolute .2
    amplitude cutoff. Candidate decoding remains scalar; numpy scans RF blocks.
    """
    def __init__(self):
        self.decoder = Decoder()
        self.tail = np.empty(0, dtype=np.float32)
        self.byte_tail = b''
        self.samples = 0

    def feed(self, data: bytes, timestamp=None):
        data = self.byte_tail + data
        self.byte_tail = data[len(data)//2*2:]
        iq = np.frombuffer(data[:len(data)//2*2], dtype=np.uint8).astype(np.float32)
        self.samples += len(iq)//2
        mag = np.hypot(iq[0::2]-127.5, iq[1::2]-127.5)/128
        mag = np.concatenate((self.tail, mag))
        count = len(mag)-239
        output = []
        if count > 0:
            pulse = np.minimum.reduce([mag[k:k+count] for k in (0,2,7,9)])
            quiet = np.maximum.reduce([mag[k:k+count] for k in (1,3,4,5,6,8,10,11,12,13,14,15)])
            candidates = np.flatnonzero((pulse > quiet*1.7) & (pulse > .025))
            skip = -1
            for i in candidates:
                if i < skip:
                    continue
                bits = mag[i+16:i+240:2] > mag[i+17:i+240:2]
                raw = np.packbits(bits).tobytes()
                f = self.decoder.decode(raw, timestamp)
                if f:
                    output.append(f)
                    skip = i+240
        self.tail = mag[-239:].copy()
        return output


class Tracker:
    def __init__(self):
        self.aircraft = {}
        self.total = 0

    def accept(self, f):
        self.total += 1
        old = self.aircraft.get(f.icao)
        if old is None:
            old = dict(icao=f.icao, first=f.timestamp, last=f.timestamp,
                       frames=0, track=[], position_time=None)
            self.aircraft[f.icao] = old
        old['frames'] += 1
        old['first'] = min(old['first'], f.timestamp)
        if f.timestamp >= old['last']:
            old['last'] = f.timestamp
            for key, value in asdict(f).items():
                if value is not None and key not in ('icao','timestamp','latitude','longitude'):
                    old[key] = value
            if valid_position(f.latitude, f.longitude):
                old.update(latitude=f.latitude, longitude=f.longitude, position_time=f.timestamp)
                track = old['track']
                if not track or f.timestamp-track[-1][0] >= 2:
                    track.append((f.timestamp, f.latitude, f.longitude, f.altitude))
                    if len(track) > 512:
                        old['track'] = track[::2] + ([track[-1]] if len(track)%2 == 0 else [])
        return old

    def live(self, now=None):
        now = time.time() if now is None else now
        stale = [k for k,v in self.aircraft.items() if now-v['last'] > 300]
        for k in stale:
            del self.aircraft[k]
        return [v for v in self.aircraft.values() if now-v['last'] <= 120]
