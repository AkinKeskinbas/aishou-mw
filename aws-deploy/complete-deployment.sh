#!/bin/bash

# Complete Aishou Backend Deployment Script
# Usage: bash complete-deployment.sh your-domain.com

set -e

DOMAIN=$1

if [ -z "$DOMAIN" ]; then
    echo "❌ Usage: bash complete-deployment.sh your-domain.com"
    echo "Example: bash complete-deployment.sh api.aishou-app.com"
    exit 1
fi

echo "🚀 Starting Complete Aishou Backend Deployment for domain: $DOMAIN"

# Step 1: Server setup (run as root)
echo "📦 Step 1: Setting up server..."
sudo bash server-setup.sh

# Step 2: Deploy application
echo "🚀 Step 2: Deploying application..."
bash deploy-app.sh

# Step 3: Configure Nginx
echo "🌐 Step 3: Configuring Nginx..."
sudo cp nginx-https.conf /etc/nginx/conf.d/aishou.conf

# Step 4: Setup SSL
echo "🔒 Step 4: Setting up SSL..."
sudo bash setup-ssl.sh $DOMAIN

# Step 5: Setup monitoring
echo "📊 Step 5: Setting up monitoring..."
sudo bash setup-monitoring.sh

# Step 6: Final health check
echo "🩺 Step 6: Health check..."
sleep 10

# Check if application is running
if sudo systemctl is-active --quiet aishou; then
    echo "✅ Application service is running"
else
    echo "❌ Application service is not running!"
    sudo journalctl -u aishou --lines=10
fi

# Check if Nginx is running
if sudo systemctl is-active --quiet nginx; then
    echo "✅ Nginx service is running"
else
    echo "❌ Nginx service is not running!"
    sudo systemctl status nginx
fi

# Test API endpoints
echo "🧪 Testing API endpoints..."

# Test HTTP redirect
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://$DOMAIN/ || echo "000")
if [ "$HTTP_STATUS" = "301" ] || [ "$HTTP_STATUS" = "302" ]; then
    echo "✅ HTTP to HTTPS redirect working"
else
    echo "⚠️ HTTP redirect status: $HTTP_STATUS"
fi

# Test HTTPS endpoint
HTTPS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" https://$DOMAIN/ || echo "000")
if [ "$HTTPS_STATUS" = "200" ]; then
    echo "✅ HTTPS endpoint working"

    # Test API endpoint
    API_STATUS=$(curl -s -o /dev/null -w "%{http_code}" https://$DOMAIN/v1/tests || echo "000")
    if [ "$API_STATUS" = "200" ]; then
        echo "✅ API endpoint working"
    else
        echo "⚠️ API endpoint status: $API_STATUS"
    fi
else
    echo "❌ HTTPS endpoint status: $HTTPS_STATUS"
fi

echo ""
echo "🎉 Deployment Summary:"
echo "======================================"
echo "Domain: https://$DOMAIN"
echo "API Base URL: https://$DOMAIN/v1"
echo "Health Check: https://$DOMAIN/"
echo ""
echo "📊 Services Status:"
echo "Application: $(sudo systemctl is-active aishou)"
echo "Nginx: $(sudo systemctl is-active nginx)"
echo "CloudWatch: $(sudo systemctl is-active amazon-cloudwatch-agent)"
echo ""
echo "📝 Important Files:"
echo "App Directory: /opt/aishou"
echo "Environment: /opt/aishou/.env"
echo "Nginx Config: /etc/nginx/conf.d/aishou.conf"
echo "SSL Certs: /etc/letsencrypt/live/$DOMAIN/"
echo ""
echo "🔧 Useful Commands:"
echo "View app logs: sudo journalctl -u aishou -f"
echo "View nginx logs: sudo tail -f /var/log/nginx/aishou_*.log"
echo "Restart app: sudo systemctl restart aishou"
echo "Restart nginx: sudo systemctl restart nginx"
echo ""
echo "🧪 Test API:"
echo "curl https://$DOMAIN/"
echo "curl https://$DOMAIN/v1/tests"
echo ""

if [ "$HTTPS_STATUS" = "200" ] && [ "$API_STATUS" = "200" ]; then
    echo "✅ Deployment completed successfully!"
    echo "🎯 Your Aishou Backend is now live at: https://$DOMAIN"
else
    echo "⚠️ Deployment completed with warnings."
    echo "Please check the logs and troubleshoot any issues."
fi

echo ""
echo "📧 Don't forget to:"
echo "1. Update your .env file with real credentials"
echo "2. Set up CloudWatch alarms"
echo "3. Configure backup strategies"
echo "4. Update your mobile app's API endpoint"