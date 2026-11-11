# SkyLog 1090 Windows — notices

Application: GPL-3.0-or-later, derived from the Android SkyLog / 9GRadio repository. Original ownership is preserved. Complete application sources and build instructions accompany this release in SkyLog1090-0.1.0-source.zip. Android files were not changed.

Bundled libraries remain separately replaceable in `_internal`. Reverse engineering for debugging modifications to LGPL libraries is permitted. This distribution uses the open-source terms, not a commercial Qt license.

| Component | Version / terms | Source |
|---|---|---|
| Qt / PySide6 Essentials / Shiboken | 6.12.0, LGPL-3.0 / GPL compatible alternatives, see bundled licenses | https://download.qt.io/archive/qt/6.12/6.12.0/submodules/ ; https://code.qt.io/pyside/pyside-setup.git/tag/?h=v6.12.0 |
| NumPy | 2.4.4, BSD, additional numerical-library notices in licenses | https://github.com/numpy/numpy/tree/v2.4.4 |
| Python | 3.14.8, PSF | https://www.python.org/downloads/release/python-3148/ |
| openpyxl / et_xmlfile | 3.1.5 / 2.0.0, MIT | https://pypi.org/project/openpyxl/ ; https://pypi.org/project/et-xmlfile/ |
| ReportLab | 5.1.0, BSD | https://pypi.org/project/reportlab/ |
| Pillow | 13.0.0, MIT-CMU and bundled library notices | https://pypi.org/project/pillow/ |
| charset-normalizer | 3.5.2, MIT | https://pypi.org/project/charset-normalizer/ |
| PyInstaller bootloader | 6.22.3, GPL with bootloader exception | https://github.com/pyinstaller/pyinstaller/tree/v6.22.3 |
| RTL-SDR Blog librtlsdr | V1.4.0, GPL-2.0-or-later | https://github.com/rtlsdrblog/rtl-sdr-blog/tree/V1.4.0 |
| libusb (dependency of official driver) | LGPL-2.1-or-later, version/build as supplied upstream | https://github.com/libusb/libusb |
| pthreads-win32 pthreadVC2.dll | LGPL, official unmodified driver dependency | https://sourceware.org/pthreads-win32/ |
| msvcr100.dll | Microsoft runtime, official unmodified driver dependency | https://github.com/rtlsdrblog/rtl-sdr-blog/releases/tag/V1.4.0 |
| Natural Earth country boundaries | 1:110m, public domain | https://www.naturalearthdata.com/about/terms-of-use/ ; https://github.com/nvkelso/natural-earth-vector |

`_internal/licenses/` contains the installed packages' full license and notice files and librtlsdr's COPYING. `_internal/vendor-sources/rtl-sdr-blog-V1.4.0.zip` contains the corresponding official driver source. Original runtime DLLs are shipped unmodified as recommended by the V4L manufacturer; SHA-256 and exact download URLs are in vendor-manifest.json. Build tooling is not required on the end user's computer.

Hardware references: https://www.rtl-sdr.com/v4l/ ; https://www.rtl-sdr.com/wp-content/uploads/2026/08/RTLSDR_V4L_Datasheet4.pdf . Antenna dimensions shown in-app are a free-space quarter-wave calculation, not a measured tuning result for the user's antenna.
