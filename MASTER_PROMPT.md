# 🎬 Video Player — V1 Master Prompt & Spec

Bu dosya, uygulamanın V1 kapsamının **tek doğru kaynağıdır (source of truth)**. Kararlar kullanıcıyla netleştirilmiştir; yeniden tartışmaya gerek yoktur. Yeni bir oturum bu dosyayı + `CLAUDE.md` + `memory/MEMORY.md` dosyasını okuyarak başlamalıdır.

---

## 0. Vizyon & Felsefe
Reklamsız, hesapsız, tamamen açık kaynak, **kullanıcı deneyimi her şeyin önünde** olan bir Android video oynatıcısı. Hedef his: **"aç ve oyna, hiç düşünme."** VLC'nin gücünü taşı ama dağınıklığını taşıma. MX Player'ın akıcı jest hissini ver ama reklamlarını verme.

**Üç değişmez ilke:**
1. **Akıcılık:** Hiçbir işlem gecikme hissettirmez. Anlık görsel geri bildirim. Keyframe seek (anlık sarma). Hızlı açılış.
2. **Varsayılan sade, isteyene derin:** İlk açılış temiz; güçlü özellikler menülerde gizli (progressive disclosure).
3. **Az dokunuş:** Tekrar eden her seçim (altyazı dili, ses pisti, hız) hatırlanır; ikinci kez sorulmaz.

## 1. Platform & Teknik Temel (KİLİTLİ)
- **Platform:** Native Android — **Kotlin + Jetpack Compose**. (Flutter/RN değil; karar kullanıcıyla verildi: "Android'de maksimum derinlik" önceliği.)
- **iOS stratejisi:** Çekirdek mantık (kütüphane tarama, oynatma durumu, altyazı yönetimi, ayarlar, dosya hafızası) Android UI'ından **ayrık, saf Kotlin modüllerinde** yazılır → iOS sırası gelince **KMP**'ye taşınır, sadece UI yeniden yazılır.
- **Oynatma motoru:** Hibrit. **Media3/ExoPlayer = varsayılan** (donanım decode, PiP, Cast, arka plan). **libmpv = güç/yedek motor** (ASS altyazı, nadir codec, Media3'ün açamadığı dosyalar). Tek bir `PlaybackEngine` arayüzü; UI hangi motorun çalıştığını bilmez. Media3 açamazsa otomatik mpv'ye düşer; kullanıcı dosya bazında elle motor seçebilir.
- **Lisans:** GPLv3. Dağıtım: önce **F-Droid**, sonra Play Store.
- **Referans kod tabanları:** NextPlayer (Media3 blueprint), mpvKt (libmpv blueprint).

## 2. Çekirdek Oynatma
- Geniş format/codec: MKV, MP4, AVI, TS, MOV, WebM, FLV, WMV, 3GP + H.264, HEVC 10-bit, AV1, VP9.
- **HW / HW+ / SW decoder** — oynatma sırasında canlı geçilebilen, kullanıcıya gösterilen tek butonlu seçim (motor yönlendirmesiyle).
- 4K/8K decode, **HDR10 + HDR→SDR tone mapping**.
- **Anlık sarma:** keyframe (I-frame) seek.
- **Sarma çubuğunda thumbnail önizleme.**
- Oynatma hızı 0.25x–4x, ince ayar, **ses perdesi koruması (pitch correction)**.
- Frame-by-frame ilerleme.
- A-B tekrar, video/liste tekrar, karıştır.
- Aspect ratio: Best Fit / Fill / 16:9 / 4:3 / Center / zoom.
- Uyku zamanlayıcısı (süre sonu **veya** "bu video bitince").
- Ekran kapalı/arka planda ses olarak çalmaya devam (MediaSession + bildirim kontrolleri).

## 3. Jestler & Kontroller (MX Player kalitesinde imza his)
Her jestte anlık görsel overlay.
- Sol yarı dikey → **parlaklık** · Sağ yarı dikey → **ses** (%200'e kadar)
- Yatay kaydırma → **sarma** (+/− göstergesiyle)
- Çift dokunma → oynat/duraklat veya 10sn atla (ayarlanabilir)
- **Uzun basılı tutma → hızlandırma (YouTube tarzı): bas 2x, bırak normal. V1'de KESİN var.**
- Pinch → zoom + pan, aspect ratio
- Tek dokunma → kontrolleri göster/gizle (otomatik gizlenir)
- **Ekran kilidi** + **Kids Lock** (dokunuş + donanım tuşlarını kilitler, köşe kombinasyonuyla açılır)
- **PiP + floating/pop-up pencere**
- Yön kilidi (yatay/dikey/oto), **dosya bazında hatırlanır**
- Jestler tek tek açılıp kapatılabilir, hassasiyet/zon özelleştirilebilir

## 4. ⭐ Altyazı Sistemi (uygulamanın yıldızı — en çok UX odağı)
**Hedef: "doğru altyazı en üstte, tek dokunuşla gelsin."**

### 4a. Favori diller (en önemli kullanıcı isteği)
- Ayarlarda **favori altyazı dilleri** (örn. Türkçe + İngilizce) en üste sabitlenir.
- Altyazı arama açıldığında **otomatik favori dillerle filtrelenmiş** gelir.
- Favori diller arası tek dokunuşla geçiş (Türkçe ⇄ İngilizce sekmesi).
- Diğer diller "Tüm diller" altında; arayan bulur, önde durmaz.

### 4b. Akıllı kalite sıralaması (kullanılabilir altyazı, göstermelik değil)
OpenSubtitles sonuçları kaliteye göre sıralanır:
- **İndirme sayısı + puana göre** sırala.
- **Release/dosya adı eşleşmesini** tespit et → "✓ Senkron uyumlu" rozeti.
- **"Makine çevirisi"**ni işaretle ve aşağı at.
- En üstteki en iyi sonuç **tek dokunuşla** inip otomatik yüklenir.

### 4c. Akıcı yükleme akışı
- Klasördeki **aynı isimli harici altyazıları otomatik bul ve yükle** (komşu altyazı taraması).
- Seçilen altyazı dili/pisti **dosya ve klasör bazında hatırlanır**.
- OpenSubtitles API anahtarı/oturumu açık kaynağa uygun çözülür.

### 4d. Görüntüleme & kontrol
- Format: SRT, **ASS/SSA (tam stil — libmpv/libass)**, VTT, SUB/IDX, PGS, gömülü + harici.
- 50ms adımlarla senkron ayarı.
- Stil: boyut, renk, arka plan/kutu, kenarlık, font.
- Çoklu altyazı pisti + harici yükleme + kapatma.

## 5. Ses
- **%200'e kadar ses yükseltme.**
- Ekolayzer + bass boost (preset + özel bant).
- Ses senkron ayarı (offset).
- Çoklu ses pisti canlı geçiş + harici ses pisti, **dosya bazında hatırlanır**.

## 6. Kütüphane, Hafıza & Dosya Yönetimi
- **Akıllı hafıza (çekirdek):** her dosya için kaldığın yer + ses pisti + altyazı pisti + hız + zoom/aspect + yön; **klasör bazında varsayılanlar**. Veri modeli V2 dil özelliklerini kaldıracak şekilde ileri görüşlü tasarlanır.
- Otomatik medya taraması, **sadece medya içeren klasörleri** gösteren temiz tarayıcı.
- Thumbnail, izleme geçmişi, "kaldığın yerden devam et" listesi.
- Sıralama/filtreleme (ad, tarih, süre), arama.
- **Klasördeki bir sonraki dosyaya otomatik geçiş.**
- **Uygulama içi dosya yönetimi:** yeniden adlandır, sil, taşı, paylaş + **çöp kutusu** (geri alınabilir silme).
- **Yer imleri (bookmarks).**
- **Ekran görüntüsü + kısa klip/GIF alma.**
- Android TV / tablet uyumlu düzen.

## 7. Ağ & Yayın
- URL'den stream (HTTP/HTTPS/HLS/RTSP).
- **SMB / NAS tarama**, FTP/SFTP; DLNA/UPnP (mümkünse V1, değilse erken V2).
- **Chromecast** (Media3 yolu).

## 8. Gizlilik & Güven (kimlik = özellik)
- **Sıfır reklam, sıfır telemetri, hesap yok, minimum izin** — açıkça öne çıkarılır.
- Tamamen offline çalışır (ağ özellikleri hariç).
- **Şifreli/gizli klasör** + **hızlı gizleme**.
- `.nomedia` klasörlerine saygı.

## 9. Arayüz & UX Prensipleri
- **Material You + dinamik renk + true-black AMOLED tema.**
- Modern, sade, video-öncelikli; kontroller otomatik gizlenir.
- **Özelleştirilebilir kontrol çubuğu** (sade isteyen temizler, power-user doldurur).
- Anlık geri bildirim; bloklamayan spinner yok; thumbnail önceden üretilir; hızlı klasör taraması.
- Erişilebilirlik: sistem altyazı stilleri, büyük dokunma hedefi, okunur renk paletleri.

## 10. V1 KAPSAMI DIŞI (bilinçli olarak yok)
- ❌ Ana ekran widget'ı
- ❌ Ayarları yedekle/geri yükle
- ❌ Ses zekâsı / gece modu / ses normalizasyonu
- ❌ Dil öğrenme özellikleri (çift altyazı+sözlük, dokun-duraklat) → **V2**
- ❌ Cihaz-içi AI altyazı (Whisper), AI upscaling, motion smoothing → sonraki sürüm
- ❌ Akıllı atlama (intro/jenerik), cihazlar arası senkron, watch-together → sonraki sürüm

## 11. V2 Park Listesi (sadece veri modeli baştan hazır)
Çift altyazı + kelimeye dokun-sözlük + altyazıya dokun-duraklat-büyüt + kullanıcının ekleyeceği diğer dil fikirleri. Dosya hafızası veri modeli bunları kaldıracak şekilde tasarlanır; başka iş yapılmaz.

---

## Önerilen Fazlı Yol Haritası
- **Faz 0 — Temel:** `git init`, Kotlin+Compose iskelet, temiz modül sınırları, `PlaybackEngine` arayüzü, Media3 motoru, dosya seçici + temel medya kütüphanesi (MediaStore), temel oynatma.
- **Faz 1 — MVP (gerçekten iyi sade oynatıcı):** tam jest seti (long-press hızlandırma dahil), klasör/kütüphane tarayıcısı, kaldığın yer + akıllı hafıza, hız, PiP, arka plan ses, temel SRT/VTT altyazı, Material You. **F-Droid'e ilk sürüm.**
- **Faz 2 — Güç özellikleri:** libmpv ikinci motor + otomatik yönlendirme, **ASS/SSA (libass) altyazı**, ⭐ favori-dilli OpenSubtitles akışı, ses pisti/passthrough, Chromecast.
- **Faz 3 — Ağ & cila:** SMB/NAS + URL stream, HDR ince ayar, ekolayzer, gizli klasör, dosya yönetimi + çöp kutusu, yer imleri, ekran görüntüsü/klip.
