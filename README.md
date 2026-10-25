# Sunplus Remote 1.0 (tam sürüm)

Ucast 5.0.1'in bütün bölümleri: **Cihaz, Kumanda, DVB, IPTV, Dosya, Web**.
Protokol, Ucast APK'sının decompile edilmesiyle çıkarıldı (vendor
dokümantasyonu yok); `libnative_lib.so` (encode/decode) APK'dan aynen alındı.

## Bölümler

- **Kumanda** (açılış ekranı): sayılar en üstte; altında çok büyük SES ± /
  KANAL ± tuşları (ortada Sessiz + Güç); sonra yön tuşları, menü/çıkış/bilgi/EPG/
  fav/kaynak/dev/ses-dil/altyazı/sayfa, renkli tuşlar, oynatma tuşları.
  SES, KANAL, yön ve sayfa tuşları basılı tutunca tekrar eder.
  Bilgi satırı ekranın en altında (bağlı cihaz, IP, portlar, son işlem).
- **Cihaz**: ağda bulunan alıcılar (UDP 8998 yayını); birini seç, hatırlanır.
- **DVB**: TV / Radyo listesi (alıcıdan çekilir), arama, filtre (uydu vb.),
  favoriler (★), şu an alıcıda açık olan kanal yeşil işaretli (5 sn'de bir
  güncellenir). Kanala dokun → telefonda izle (gerekirse alıcı o kanala geçer,
  önce sorar). Uzun bas → "Alıcıda bu kanala geç" / favori.
- **IPTV**: alıcıdaki IPTV uygulamaları → gruplar → kanallar, telefonda oynatır.
  (Alıcı IPTV'yi desteklemiyorsa — `appstore=0` — ekranda yazar.)
- **Dosya**: telefondaki video/müzik/resimleri alıcıya gönderip oynatır. Telefon
  yerel ağda küçük bir HTTP sunucusu açar, alıcı dosyayı oradan çeker.
- **Web**: gömülü tarayıcı; sayfada geçen video adreslerini (m3u8, mp4, ...)
  yakalar, 🎬 sayacına dokununca alıcıya gönderir.

## APK nasıl alınır

**A) GitHub Actions (Android Studio gerekmez):** klasörü GitHub reposuna push'la →
Actions sekmesi → son çalışma → Artifacts → `sunplus-remote-debug-apk`.

**B) Android Studio:** `File > Open` ile klasörü aç, sync bitince Run.

Önce eski sürümü kaldırmana gerek yok (aynı paket adı, üzerine kurulur).

## Kullanırken

- Telefon ve alıcı aynı Wi-Fi'de olmalı. Orijinal Ucast açıksa kapat (aynı
  8998 portunu dinliyor).
- Dosya / Web ile alıcıya bir şey gönderdikten sonra oynatma bitene kadar
  uygulamayı açık tut (dosyayı telefon sunuyor).
- Telefonda canlı izleme ExoPlayer ile; bazı telefonlar MP2/AC3 ses
  kodeklerini çözemez (görüntü var, ses yok). Oynatıcı ekranında
  **Harici oynatıcı** düğmesi var (VLC önerilir).
- Dosya sekmesi ilk açılışta medya erişim izni ister.

## Bilinen eksikler

- YouTube vb. sitelerin video çözme (şifre çözme) kısmı yok; Web sekmesi
  yalnızca sayfanın yüklediği doğrudan medya adreslerini yakalar.
- Orijinaldeki "geçmiş" listesi ve IPTV oynatma kontrolleri (timeshift vb.) yok.
- Gerçek alıcıyla denenmedi (sadece ağ katmanı, sahte bir alıcıyla uçtan uca
  test edildi: UDP istek → telefondaki HTTP geri çağrı, paket kaybında yeniden
  deneme, ~300 KB'lık kanal listesi). Bir şey ters giderse Cihaz sekmesindeki
  sPort/dvbSPort değerleriyle birlikte yaz.

## Teknik özet

- Her mesaj: JSON → `LocalApplication.encodeString` → `json=` önekiyle tek UDP paketi.
- Tuş / yayınlama komutları `s_port`'a gider ve cevap beklenmez
  (`{"len","name","type","url","use"}`, anahtarlar orijinaldeki sırayla).
- Cevap gereken istekler (`/json/channel/...`, IPTV) `dvb_s_port` / `s_port`'a
  gider; JSON içindeki `url` alanı telefondaki sunucuyu (`9901+`) gösterir,
  alıcı cevabı oraya POST eder.
- Canlı yayın adresi: `http://<alıcı-ip>:9891/live/<kanal-id>`.
- `app/src/main/java/com/example/ucast/LocalApplication.kt` paketi/adı
  değiştirilmemeli: native kütüphane JNI'yi bu sınıf yoluna sabit kaydediyor.
