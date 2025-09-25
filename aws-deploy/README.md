# 🚀 Aishou Backend AWS Deployment Package

Bu paket, Aishou Backend uygulamasını AWS EC2 (Amazon Linux 2023) üzerinde deploy etmek için gerekli tüm dosyaları içerir.

## 📦 Paket İçeriği

```
aws-deploy/
├── server-setup.sh           # Server kurulum script'i
├── deploy-app.sh             # Uygulama deployment script'i
├── complete-deployment.sh    # Tek komutla tam deployment
├── nginx-https.conf          # Nginx HTTPS konfigürasyonu
├── setup-ssl.sh              # SSL certificate kurulum script'i
├── setup-monitoring.sh       # CloudWatch monitoring kurulumu
├── cloudwatch-agent.json     # CloudWatch agent konfigürasyonu
├── sample.env                # Örnek environment dosyası
└── README.md                 # Bu dosya
```

## 🎯 Hızlı Deployment (Tek Komut)

### 1. EC2 Instance'a Dosyaları Yükle

```bash
# Distribution dosyasını EC2'ye kopyala
scp -i "your-key.pem" ../build/distributions/aishou.tar ec2-user@YOUR-IP:/opt/aishou/

# Deployment script'lerini kopyala
scp -i "your-key.pem" -r aws-deploy/ ec2-user@YOUR-IP:/home/ec2-user/
```

### 2. Tek Komutla Deployment

```bash
# EC2'ye bağlan
ssh -i "your-key.pem" ec2-user@YOUR-IP

# Deployment script'lerini çalıştırılabilir yap
chmod +x aws-deploy/*.sh

# Tek komutla tüm deployment'ı yap
cd aws-deploy
bash complete-deployment.sh api.yourdomain.com
```

## 📋 Manuel Deployment (Adım Adım)

### 1. Server Kurulumu

```bash
# Root olarak çalıştır
sudo bash server-setup.sh
```

**Yaptıkları:**
- Java 17 kurulumu
- Nginx kurulumu
- Git, htop, curl kurulumu
- Certbot (SSL) kurulumu
- CloudWatch Agent kurulumu
- Fail2ban güvenlik kurulumu
- Otomatik güncellemeler

### 2. Uygulama Deployment

```bash
# Normal kullanıcı olarak çalıştır
bash deploy-app.sh
```

**Yaptıkları:**
- Uygulama dosyalarını ayıklar
- Systemd service oluşturur
- .env dosyası şablonu oluşturur
- Uygulamayı başlatır

### 3. Nginx HTTPS Konfigürasyonu

```bash
# Nginx config'i kopyala
sudo cp nginx-https.conf /etc/nginx/conf.d/aishou.conf

# Domain adını güncelle
sudo nano /etc/nginx/conf.d/aishou.conf
# api.yourdomain.com → gerçek domain'iniz

# Nginx test ve restart
sudo nginx -t
sudo systemctl restart nginx
```

### 4. SSL Certificate Kurulumu

```bash
# SSL kurulumu
sudo bash setup-ssl.sh api.yourdomain.com
```

**Yaptıkları:**
- Let's Encrypt SSL certificate alır
- Nginx konfigürasyonunu günceller
- Auto-renewal ayarlar
- HTTPS'i test eder

### 5. Monitoring Kurulumu

```bash
# CloudWatch monitoring
sudo bash setup-monitoring.sh
```

**Yaptıkları:**
- CloudWatch Agent yapılandırır
- Log collection ayarlar
- Metrics collection başlatır

## ⚙️ Environment Variables

### .env Dosyası Düzenleme

```bash
# Environment dosyasını düzenle
nano /opt/aishou/.env
```

**Zorunlu değişkenler:**

```bash
JWT_SECRET=your-256-bit-secret-key
MONGO_URI=mongodb+srv://user:pass@cluster.mongodb.net/aishou
ONESIGNAL_APP_ID=your-onesignal-app-id
ONESIGNAL_REST_KEY=your-onesignal-rest-key
OPENAI_API_KEY=your-openai-key
PUBLIC_BASE_URL=https://api.yourdomain.com
```

### Değişiklik Sonrası Restart

```bash
sudo systemctl restart aishou
```

## 🔧 AWS Konfigürasyonu

### EC2 Security Groups

Aşağıdaki portları açın:

```
Inbound Rules:
- SSH (22): Your IP
- HTTP (80): 0.0.0.0/0
- HTTPS (443): 0.0.0.0/0
- Custom TCP (8080): 0.0.0.0/0 (opsiyonel, debug için)
```

### IAM Role (CloudWatch için)

```bash
1. IAM → Roles → Create role
2. Service: EC2
3. Policy: CloudWatchAgentServerPolicy
4. Role name: CloudWatchAgentServerRole
5. EC2 instance'a bu role'ü attach edin
```

### Route 53 DNS

```bash
A Record:
- Name: api
- Value: Your Elastic IP
- TTL: 300
```

## 🩺 Health Check & Troubleshooting

### Service Status

```bash
# Tüm servislerin durumu
sudo systemctl status aishou nginx amazon-cloudwatch-agent

# Uygulama logları
sudo journalctl -u aishou -f

# Nginx logları
sudo tail -f /var/log/nginx/aishou_access.log
sudo tail -f /var/log/nginx/aishou_error.log
```

### API Test

```bash
# Health check
curl https://api.yourdomain.com/

# API endpoints
curl https://api.yourdomain.com/v1/tests
curl -H "Accept-Language: ja" https://api.yourdomain.com/v1/tests
```

### Yaygın Sorunlar

**1. Application başlamıyor:**
```bash
# Logları kontrol et
sudo journalctl -u aishou --lines=50

# .env dosyasını kontrol et
cat /opt/aishou/.env

# Java version kontrol
java -version
```

**2. SSL certificate alınamıyor:**
```bash
# DNS kontrolü
nslookup api.yourdomain.com

# Port kontrolü
netstat -tlnp | grep :80
netstat -tlnp | grep :443

# Certbot manual çalıştır
sudo certbot --nginx -d api.yourdomain.com -v
```

**3. CORS hataları:**
```bash
# Nginx config kontrol
sudo nginx -t

# CORS headers kontrol
curl -H "Origin: https://yourapp.com" \
     -H "Access-Control-Request-Method: POST" \
     -H "Access-Control-Request-Headers: X-Requested-With" \
     -X OPTIONS https://api.yourdomain.com/v1/tests
```

## 📊 Monitoring & Alerts

### CloudWatch Metrics

Otomatik olarak toplanan metrikler:
- CPU, Memory, Disk kullanımı
- Network trafiği
- Application logları
- Nginx access/error logları

### CloudWatch Alarms Önerileri

```bash
1. CPU > 80% (2 dakika)
2. Memory > 85% (5 dakika)
3. Disk > 80% (5 dakika)
4. HTTP 5xx errors > 10 (5 dakika)
```

## 🔄 Maintenance

### Auto Updates

```bash
# Yum-cron status
sudo systemctl status yum-cron

# SSL renewal test
sudo certbot renew --dry-run

# Log rotation
sudo logrotate -f /etc/logrotate.d/aishou
```

### Backup

```bash
# Application backup
sudo tar -czf /opt/backup/aishou-$(date +%Y%m%d).tar.gz /opt/aishou

# MongoDB backup (Atlas otomatik yapar)
# SSL certificates backup (Let's Encrypt otomatik yeniler)
```

## 🚀 Deployment Güncellemeleri

### Yeni Version Deploy

```bash
# Yeni JAR dosyasını yükle
scp -i "your-key.pem" new-aishou.tar ec2-user@YOUR-IP:/opt/aishou/

# Application'ı güncelle
cd /opt/aishou
tar -xf new-aishou.tar
sudo systemctl restart aishou

# Health check
curl https://api.yourdomain.com/v1/tests
```

## 📞 Destek

Sorun yaşamanız durumunda:

1. Logları kontrol edin
2. Service status'ları kontrol edin
3. Gerekli configuration dosyalarını kontrol edin
4. AWS CloudWatch'da metrikleri inceleyin

**Deployment başarılı olduğunda API'nız şu adreste çalışacak:**
- **Base URL:** `https://api.yourdomain.com`
- **Health Check:** `https://api.yourdomain.com/`
- **API Endpoints:** `https://api.yourdomain.com/v1/*`

🎉 **Deployment başarılı!**