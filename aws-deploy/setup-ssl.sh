#!/bin/bash

# SSL Certificate Setup Script for Aishou Backend
# Usage: sudo bash setup-ssl.sh your-domain.com

set -e

DOMAIN=$1

if [ -z "$DOMAIN" ]; then
    echo "❌ Usage: sudo bash setup-ssl.sh your-domain.com"
    echo "Example: sudo bash setup-ssl.sh api.aishou-app.com"
    exit 1
fi

echo "🔒 Setting up SSL certificate for domain: $DOMAIN"

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo "❌ This script must be run as root"
   echo "Please run as: sudo bash setup-ssl.sh $DOMAIN"
   exit 1
fi

# Update nginx configuration with correct domain
echo "📝 Updating Nginx configuration..."
sed -i "s/api.yourdomain.com/$DOMAIN/g" /etc/nginx/conf.d/aishou.conf

# Test nginx configuration
echo "🧪 Testing Nginx configuration..."
nginx -t

if [ $? -ne 0 ]; then
    echo "❌ Nginx configuration test failed!"
    exit 1
fi

# Restart nginx
echo "🔄 Restarting Nginx..."
systemctl restart nginx

# Check if nginx is running
if ! systemctl is-active --quiet nginx; then
    echo "❌ Nginx failed to start!"
    systemctl status nginx
    exit 1
fi

# Get SSL certificate from Let's Encrypt
echo "📜 Obtaining SSL certificate from Let's Encrypt..."
certbot --nginx -d $DOMAIN --non-interactive --agree-tos --email admin@$DOMAIN

if [ $? -eq 0 ]; then
    echo "✅ SSL certificate obtained successfully!"

    # Set up auto-renewal
    echo "🔄 Setting up SSL certificate auto-renewal..."
    (crontab -l 2>/dev/null; echo "0 12 * * * /usr/bin/certbot renew --quiet --nginx") | crontab -

    # Test the renewal process
    echo "🧪 Testing SSL certificate renewal..."
    certbot renew --dry-run

    if [ $? -eq 0 ]; then
        echo "✅ SSL certificate auto-renewal is configured correctly!"
    else
        echo "⚠️ SSL certificate auto-renewal test failed, but certificate is still valid"
    fi

    # Final nginx restart
    systemctl restart nginx

    echo "🎉 SSL setup completed successfully!"
    echo ""
    echo "📝 SSL Certificate Information:"
    echo "Domain: $DOMAIN"
    echo "Certificate: /etc/letsencrypt/live/$DOMAIN/fullchain.pem"
    echo "Private Key: /etc/letsencrypt/live/$DOMAIN/privkey.pem"
    echo "Expires: $(date -d '+90 days' '+%Y-%m-%d')"
    echo ""
    echo "🌐 Your API is now available at: https://$DOMAIN"
    echo ""
    echo "🧪 Test your API:"
    echo "curl -k https://$DOMAIN/"
    echo "curl -k https://$DOMAIN/v1/tests"

else
    echo "❌ Failed to obtain SSL certificate!"
    echo "Please check:"
    echo "1. Domain DNS is pointing to this server"
    echo "2. Port 80 and 443 are open in security groups"
    echo "3. No other service is using port 80"
    exit 1
fi