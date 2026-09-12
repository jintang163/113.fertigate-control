"""参考作物蒸散 ET0：Hargreaves-Samani（仅需气温与纬度），FAO-56 式(52)。

ET0 = 0.0023 (Tmean + 17.8) (Tmax - Tmin)^0.5 Ra          [mm/day]
其中 Ra 为地外辐射，单位 MJ m-2 day-1，由纬度与日序解析计算。
"""
from __future__ import annotations

import math

# 太阳常数 Gsc, MJ m-2 min-1
_GSC = 0.0820


def _solar_declination(doy: int) -> float:
    return 0.409 * math.sin(2.0 * math.pi * doy / 365.0 - 1.39)


def _sunset_hour_angle(lat_rad: float, decl: float) -> float:
    x = -math.tan(lat_rad) * math.tan(decl)
    x = min(1.0, max(-1.0, x))
    return math.acos(x)


def extraterrestrial_radiation(doy: int, latitude_deg: float) -> float:
    """Ra [MJ m-2 day-1]，FAO-56 式(21)/(22)。"""
    lat = math.radians(latitude_deg)
    decl = _solar_declination(doy)
    ws = _sunset_hour_angle(lat, decl)
    dr = 1.0 + 0.033 * math.cos(2.0 * math.pi * doy / 365.0)
    ra = (24.0 * 60.0 / math.pi) * _GSC * dr * (
        ws * math.sin(lat) * math.sin(decl)
        + math.cos(lat) * math.cos(decl) * math.sin(ws)
    )
    return ra


def et0_hargreaves(t_mean: float, t_max: float, t_min: float,
                   doy: int, latitude_deg: float) -> float:
    ra = extraterrestrial_radiation(doy, latitude_deg)
    et0 = 0.0023 * (t_mean + 17.8) * math.sqrt(max(0.0, t_max - t_min)) * ra
    return max(0.0, et0)
