#!/bin/bash

# Aishou Backend AWS Server Setup Script for Amazon Linux 2023
# Usage: sudo bash server-setup.sh

set -e

echo "🚀 Starting Aishou Backend Server Setup on Amazon Linux..."

# Update system
echo "📦 Updating system packages..."
yum update -y

# Install Java 17
echo "☕ Installing Java 17..."
yum install java-17-amazon-corretto-devel -y

# Install Nginx
echo "🌐 Installing Nginx..."
yum install nginx -y

# Install Git
echo "📚 Installing Git..."
yum install git -y

# Install additional tools
echo "🔧 Installing additional tools..."
yum install htop wget curl unzip -y

# Install EPEL repository for certbot
echo "📋 Installing EPEL repository..."
yum install epel-release -y

# Install Certbot for SSL
echo "🔒 Installing Certbot..."
yum install certbot python3-certbot-nginx -y

# Install CloudWatch Agent
echo "📊 Installing CloudWatch Agent..."
wget https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm
rpm -U ./amazon-cloudwatch-agent.rpm
rm -f ./amazon-cloudwatch-agent.rpm

# Install fail2ban for security
echo "🛡️ Installing fail2ban..."
yum install fail2ban -y

# Install yum-cron for automatic updates
echo "🔄 Installing automatic updates..."
yum install yum-cron -y

# Create aishou user and directory structure
echo "👤 Creating aishou user..."
useradd -m aishou || true
mkdir -p /opt/aishou
chown -R aishou:aishou /opt/aishou

# Set up Java environment
echo "☕ Setting up Java environment..."
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto' >> /etc/environment
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/environment
source /etc/environment

# Configure firewall (if enabled)
echo "🔥 Configuring firewall..."
if systemctl is-active --quiet firewalld; then
    firewall-cmd --permanent --add-service=http
    firewall-cmd --permanent --add-service=https
    firewall-cmd --permanent --add-port=8080/tcp
    firewall-cmd --reload
fi

# Start and enable services
echo "🔄 Starting services..."
systemctl start nginx
systemctl enable nginx
systemctl start fail2ban
systemctl enable fail2ban
systemctl start yum-cron
systemctl enable yum-cron

# Java version check
echo "☕ Java version:"
java -version

echo "✅ Server setup completed successfully!"
echo "📝 Next steps:"
echo "1. Upload your application files to /opt/aishou/"
echo "2. Set up environment variables in /opt/aishou/.env"
echo "3. Create systemd service for the application"
echo "4. Configure Nginx reverse proxy"
echo "5. Set up SSL certificate with certbot"

echo "🎉 Server is ready for Aishou Backend deployment!"