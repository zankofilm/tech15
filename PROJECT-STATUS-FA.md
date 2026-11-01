# وضعیت پروژه — 2.2.1 Card Fix

- رابط قدیمی وب: هرگز نمایش داده نمی‌شود.
- Login/MFA/Biometric: Native.
- Dashboard و Navigation: Native Compose.
- همه Featureها: از نشست واقعی معلم استفاده می‌کنند.
- WebView قدیمی‌تر: دارای Session Restore سازگار و بدون نیاز به DOCUMENT_START_SCRIPT.
- Route resolver: شناسه، onclick/href/data-* و عناوین واقعی فارسی را بررسی می‌کند.
- Feature renderer: Native و دارای refresh دوم برای داده‌های async.
- File picker/download/preview/print bridge: فعال.
