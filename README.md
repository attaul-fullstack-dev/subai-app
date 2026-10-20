# SubAI Translator (AI Video Subtitle Generator & Translator)

Aplikasi Android untuk memutar video, menghasilkan subtitle otomatis, dan menerjemahkannya ke berbagai bahasa (Indonesia, Inggris, Jepang, Korea, Spanyol, dll.) secara gratis menggunakan Google ML Kit On-Device Translation.

## Fitur Utama
1. **Pemutar Video Terintegrasi:** Menggunakan Google Media3 ExoPlayer.
2. **Translasi AI On-Device & Offline:** Didukung oleh Google ML Kit Translate (tanpa biaya API).
3. **Sinkronisasi Subtitle Real-time:** Teks subtitle otomatis berganti sesuai durasi playback video.
4. **Export Subtitle:** Simpan hasil subtitle terjemahan ke file `.srt` standar.
5. **Auto-Build APK:** Terintegrasi dengan GitHub Actions CI/CD untuk otomatis mengompilasi file `.apk` siap download.

## Cara Download APK dari GitHub Actions
1. Push repository ini ke akun GitHub Anda.
2. Buka tab **Actions** di GitHub repository Anda.
3. Tunggu workflow **Build SubAI Android APK** selesai (~2 menit).
4. Klik workflow run tersebut, lalu unduh artifact **SubAI-Translator-APK** (`app-debug.apk`).
5. Install di HP Android Anda!
