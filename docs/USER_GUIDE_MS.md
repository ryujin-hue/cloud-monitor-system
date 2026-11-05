# Panduan Pengguna (Bahasa Melayu)

Panduan ini ditulis untuk pengguna baharu. Anda tidak perlu tahu tentang pengaturcaraan
untuk menggunakannya.

---

## 1. Apakah Cloud Monitor dan Cloud Alert?

Projek ini mempunyai **dua aplikasi Android** yang berbeza. Ia dipasang pada **dua telefon
yang berlainan**.

| Aplikasi | Dipasang pada | Fungsinya |
|----------|---------------|-----------|
| **Cloud Monitor** (`com.cloudmonitor`) | Telefon "awan" / telefon yang dipantau (contohnya telefon yang menjalankan Discord sepanjang masa) | Memerhati aplikasi yang anda pilih dan pemberitahuan *screen share* Discord. Ia menghantar laporan (heartbeat) dan peristiwa ke pelayan. |
| **Cloud Alert** (`com.cloudalert`) | Telefon peribadi anda | Menunjukkan keadaan semua telefon awan, dan memberi amaran (pemberitahuan) apabila ada aplikasi yang berhenti atau *screen share* terputus. |

Di tengah-tengah ada **pelayan (backend)** di Cloudflare. Kedua-dua aplikasi bercakap
dengan pelayan itu melalui Internet. Firebase hanya digunakan untuk menghantar
pemberitahuan tolak (push) — data status anda **tidak** disimpan di Firebase.

```
[Telefon awan: Cloud Monitor] --Internet--> [Pelayan Cloudflare] <--Internet-- [Telefon anda: Cloud Alert]
```

> **Nota penting:** Cloud Alert **tidak** membaca pemberitahuan telefon anda, tidak
> memerlukan akses penggunaan (Usage Access) dan tidak memerlukan Accessibility. Ia cuma
> memerlukan kebenaran pemberitahuan dan kunci API.

---

## 2. Pemasangan

1. Dapatkan fail APK daripada pentadbir projek (atau hasil binaan `assembleDebug` /
   keluaran GitHub Release).
2. Ada **dua fail APK**: satu untuk Cloud Monitor, satu untuk Cloud Alert. Pastikan anda
   pasang pada telefon yang betul.
3. Pada telefon, buka fail APK tersebut. Jika Android bertanya, pilih **"Pasang juga" / "Allow
   from this source"** hanya untuk sumber yang anda percayai. Ini adalah pemasangan terus
   (sideload), bukan dari Google Play.
4. Selepas pasang, jangan padam fail APK sehingga anda pasti kemas kini seterusnya berjaya.

**Keperluan minimum:** Android 8.0 (API 26) ke atas. Sasaran utama projek ialah
**Android 16 (API 36)**.

> Jangan sekali-kali mematikan ciri keselamatan Android (contohnya *Verify apps*, *Play
> Protect*, atau kunci skrin) semata-mata untuk memasang aplikasi ini. Jika pemasangan
> disekat, minta pentadbir projek menyemak tandatangan APK.

---

## 3. Persediaan kali pertama

### 3.1 Di telefon awan (Cloud Monitor)

1. Buka **Cloud Monitor**.
2. Anda akan nampak skrin **"Permissions required"**. Lengkapkan satu per satu (lihat
   bahagian 4).
3. Pergi ke **Settings**, tampal **kunci API monitor** yang diberi oleh pentadbir, kemudian
   tekan **Save key**. Kunci ini disimpan dalam bentuk tersulit menggunakan Android
   Keystore.
4. Pergi ke **Choose apps**, pilih aplikasi yang ingin dipantau.
5. (Pilihan) Untuk memantau *screen share* Discord: mulakan *screen share*, buka
   **Notification inspector**, kemudian tekan **"Use as Discord target"** pada
   pemberitahuan yang berkenaan.
6. Di **Dashboard**, catat **kod 6 digit** (pairing code). Kod ini tamat tempoh selepas
   **30 minit**. Tekan **New code** jika perlu kod baharu.

### 3.2 Di telefon peribadi anda (Cloud Alert)

1. Buka **Cloud Alert**.
2. Jika ada jalur (banner) **"Alerts are hidden"**, tekan **Allow notifications** dan
   benarkan. (Lihat bahagian 4.)
3. Pergi ke **Settings**, tampal **kunci API alert** (kunci yang **berbeza** daripada kunci
   monitor), tekan **Save key**, kemudian **Register**.
4. Tekan **Pair**, masukkan kod 6 digit dari telefon awan.
5. Ulangi untuk setiap telefon awan. Cloud Alert boleh memantau berbilang telefon.

> **Jangan kongsi kunci API atau kod pairing di dalam repositori, log, atau dokumen.**
> Kod pairing hanya sah 30 minit.

---

## 4. Penjelasan setiap kebenaran

### Cloud Monitor

| Kebenaran | Cara memberi | Kenapa perlu |
|-----------|--------------|--------------|
| **Notification access** (Akses pemberitahuan) | Tetapan → Pemberitahuan → *Device & app notifications* → Cloud Monitor → hidupkan | Cara utama untuk mengesan bila pemberitahuan berterusan sesuatu aplikasi hilang, dan bila pemberitahuan *screen share* Discord muncul/hilang. Android tiada dialog runtime untuk ini; ia mesti dihidupkan di Tetapan. |
| **Usage access** (Akses penggunaan) | Tetapan → Apl → *Special app access* → *Usage access* → Cloud Monitor | Untuk mengetahui bila aplikasi bertukar dari depan ke belakang. |
| **Ignore battery optimisations** (Kecualikan pengoptimuman bateri) | Butang **Allow** pada skrin kebenaran | Supaya perkhidmatan tidak "dibekukan" semasa skrin padam / mod Doze. Ini **tidak** mematikan keselamatan Android; ia hanya mengecualikan satu aplikasi ini daripada Doze. |
| **Post notifications** (Hantar pemberitahuan) | Butang **Allow** (Android 13+) | Untuk pemberitahuan status "monitor sedang berjalan". Jika anda pilih *"Don't ask again"*, gunakan butang **App notification settings**. |
| **Accessibility** (pilihan) | Tetapan → Accessibility → *Cloud Monitor ANR watcher* | Hanya untuk mengesan dialog "aplikasi tidak responsif" (ANR). Tanpa ini, ANR dilaporkan sebagai `E-101` dengan keyakinan rendah. **Boleh dibiarkan off.** |
| **Autostart OEM** (Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus, Asus) | Tetapan pengeluar → *Auto start* / *Protected apps* | Sesetengah pengeluar membunuh perkhidmatan latar secara berasingan daripada Android. |

### Cloud Alert

| Kebenaran | Kenapa perlu |
|-----------|--------------|
| **Post notifications** (Android 13+ / Android 16) | Supaya amaran gangguan dapat dipaparkan. Diminta hanya apabila aplikasi dibuka dan kebenaran masih tiada. |
| Internet | Untuk bercakap dengan pelayan. |

Cloud Alert **tidak** meminta Notification access, Usage access, Accessibility, lokasi,
kamera atau mikrofon.

### Jika kebenaran ditolak

- Aplikasi **tidak akan ranap (crash)**. Skrin kebenaran akan terus menunjukkan status
  **MISSING**.
- Jika kebenaran ditolak secara kekal (*"Don't ask again"*), gunakan butang **Open
  settings** / **App notification settings** untuk membuka halaman tetapan yang betul.
- Kebenaran khas (Notification access, Usage access, Accessibility) tidak mempunyai dialog
  runtime — ia mesti dihidupkan terus di Tetapan sistem.

---

## 5. Memulakan dan menghentikan pemantauan

- **Mula:** Cloud Monitor cuba memulakan perkhidmatan secara automatik apabila aplikasi
  dibuka. Anda juga boleh menekan **Start / Restart** di Dashboard.
- **Henti:** tekan **Stop** di Dashboard.
- **Heartbeat now:** menghantar satu laporan status serta-merta.

> **Penting:** Aplikasi hanya memaparkan mesej **"Monitor started"** selepas perkhidmatan
> benar-benar berjaya masuk ke *foreground*. Jika Android menyekatnya, mesej ralat yang
> jelas akan dipaparkan (snackbar) — bukan mesej kejayaan palsu.

Selepas telefon **but semula (reboot)** atau selepas **kemas kini APK**, perkhidmatan akan
cuba dimulakan semula secara automatik.

---

## 6. Menyemak status aplikasi

Pada **Dashboard Cloud Monitor** anda akan nampak:

- **Monitor is running / Monitor is stopped** — status sebenar perkhidmatan.
- **Device id** — pengenalan tetap telefon awan ini. Jika tertera *"not initialised yet"*,
  tunggu beberapa saat atau tekan Start.
- **Version** — versi Cloud Monitor.
- **Detection tier** — `tier 2 (privileged)` jika akses root diberikan, selalunya
  `tier 1 (no privileges)`.
- **Root check** — penjelasan ringkas hasil pemeriksaan root (lihat bahagian 7).
- **Queued events** — bilangan peristiwa yang belum berjaya dihantar (akan dihantar semula
  bila Internet kembali).
- **Pairing code** dan senarai **Watched apps**.

Pada **Dashboard Cloud Alert** anda akan nampak setiap telefon awan dengan keadaan
`ONLINE`, `OFFLINE` atau `STALE`, versi Cloud Monitor, dan bilangan insiden terbuka.
Halaman **History** boleh ditapis mengikut kod ralat.

---

## 7. Konfigurasi pemberitahuan

1. Pastikan kebenaran **Post notifications** dihidupkan (bahagian 4).
2. Di Tetapan → Apl → Cloud Alert/Cloud Monitor → Pemberitahuan, pastikan saluran
   **"Cloud alerts"** (amaran) dan **"Monitor service"** / **"Alert service"** (status)
   tidak disenyapkan.
3. Pemberitahuan status perkhidmatan sengaja dibuat senyap dan berterusan — ini adalah
   keperluan Android untuk perkhidmatan *foreground*, bukan iklan.
4. Jika mahu amaran berbunyi, naikkan kepentingan saluran **"Cloud alerts"** di Tetapan.

**Root (jika perlu):** Cloud Monitor boleh menggunakan pemeriksaan bertaraf lebih tinggi
(*tier 2*) hanya jika peranti di-root **dan** aplikasi ini diberi kebenaran root oleh
pengurus root (Magisk/KernelSU). Kewujudan fail `su` sahaja **tidak** dianggap sebagai
akses root. Jika root tidak dikesan, aplikasi kekal berfungsi dengan *tier 1*. Lihat
`docs/TROUBLESHOOTING.md`.

---

## 8. Nota keserasian Android 16 (API 36)

- Kedua-dua aplikasi kini menyasarkan **targetSdk 36** (Android 16). `compileSdk` ialah 37
  kerana pustaka AndroidX / Compose yang diguna memerlukannya; ini hanya mempengaruhi
  proses kompilasi, bukan tingkah laku semasa aplikasi berjalan pada telefon anda.
- **Edge-to-edge**: pada Android 16 aplikasi tidak boleh lagi "opt-out" daripada paparan
  tepi-ke-tepi. Aplikasi ini sudah menggunakan `enableEdgeToEdge()` dan `Scaffold`, jadi
  kandungan tidak bertindih dengan bar status / bar navigasi.
- **Predictive back**: animasi kembali sistem dihidupkan secara lalai. Aplikasi ini
  menggunakan navigasi Compose yang serasi.
- **Kebenaran pemberitahuan**: Android 13+ (termasuk 16) memerlukan `POST_NOTIFICATIONS`.
  Tanpanya amaran disembunyikan oleh sistem.
- **Sekatan permulaan latar (Android 12+)**: Android boleh menolak permulaan
  perkhidmatan *foreground* dari latar belakang. `BOOT_COMPLETED` dan
  `MY_PACKAGE_REPLACED` dikecualikan, tetapi *watchdog* (WorkManager) **tidak** dikecualikan.
  Oleh itu, jika perkhidmatan terhenti, **buka aplikasi dan tekan Start** — itu tindakan
  yang dibenarkan. Menetapkan bateri kepada **Unrestricted** juga membantu.
- **Android 16: kuota kerja latar**. Kerja yang dijadualkan dari perkhidmatan *foreground*
  kini tertakluk kepada kuota JobScheduler/WorkManager. *Watchdog* berjalan setiap 15
  minit dan tidak boleh diharapkan untuk memulakan semula perkhidmatan dari latar.
- **Jenis perkhidmatan**: perkhidmatan diisytiharkan sebagai `specialUse` dengan
  justifikasi, serta kebenaran `FOREGROUND_SERVICE_SPECIAL_USE` (keperluan Android 14+).
- **Saiz halaman 16 KB**: sesetengah peranti Android 15/16 memerlukan pustaka native
  sejajar 16 KB. Ini perlu disemak pada peranti sebenar (lihat "Had yang diketahui" dalam
  README).
- **Safer Intents / Local Network Protection** adalah ciri opt-in pada Android 16 dan tidak
  menjejaskan aplikasi ini pada masa ini.

> Jangan mematikan *Play Protect*, kunci skrin, atau penyulitan peranti untuk "membetulkan"
> sekatan Android 16. Langkah yang disyorkan hanyalah memberi kebenaran yang memang
> diperlukan dan menetapkan bateri kepada Unrestricted untuk aplikasi ini sahaja.

---

## 9. Masalah biasa dan penyelesaian ringkas

| Masalah | Penyelesaian ringkas |
|---------|----------------------|
| Aplikasi terus ranap bila dibuka | Pastikan anda guna APK yang betul dan versi terkini. Lihat `docs/TROUBLESHOOTING.md`. |
| Cloud Alert gagal dibuka | Versi lama mempunyai pepijat pada `AlertViewModel` (tiada `@HiltViewModel`). Kemas kini ke binaan yang telah dibaiki. |
| Tiada pemberitahuan amaran | Hidupkan **Post notifications** dan semak saluran "Cloud alerts". |
| Pemantauan berhenti bila skrin padam | Benarkan **Ignore battery optimisations**, dan hidupkan *Autostart* pada telefon OEM. |
| Status masih "MISSING" walaupun sudah dibenarkan | Tekan **Check again**; kebenaran khas kadang-kadang mengambil masa untuk disegerakkan. |
| Root tidak dikesan | Semak `Root check` di Dashboard. Kewujudan `su` sahaja tidak memadai — beri kebenaran root kepada aplikasi ini. |
| API gagal disambung | Semak Internet, kunci API, dan sama ada pelayan Cloudflare beroperasi. |
| Kod pairing tidak diterima | Kod tamat selepas 30 minit; tekan **New code** di telefon awan. |

Senarai penuh ada di **[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md)**.

---

## 10. Menghubungi pentadbir projek

Sila hubungi **pentadbir projek / pengurus repositori** anda (pemilik repositori
`cloud-monitor-system`) melalui saluran yang digunakan oleh organisasi anda.

Sertakan maklumat berikut supaya masalah cepat diselesaikan:

1. Nama aplikasi (Cloud Monitor atau Cloud Alert) dan versi (Dashboard → Version).
2. Model telefon dan versi Android (Tetapan → About phone).
3. Mesej ralat tepat yang dipaparkan (atau tangkapan skrin).
4. Status kebenaran pada skrin *Permissions required*.
5. Nilai **Root check** dan **Detection tier** (untuk Cloud Monitor).
6. Masa kejadian dan sama ada telefon baru sahaja but semula / dikemas kini.

**Jangan** hantar kunci API, token FCM, kata laluan, atau sebarang rahsia dalam laporan,
log, atau tiket. Kunci dan token tidak pernah dimasukkan ke dalam sumber atau dokumen
projek.
