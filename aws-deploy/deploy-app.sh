#!/bin/bash

# Aishou Backend Application Deployment Script
# Usage: bash deploy-app.sh

set -e

APP_USER="aishou"
APP_DIR="/opt/aishou"
SERVICE_NAME="aishou"

echo "🚀 Starting Aishou Backend Application Deployment..."

# Check if running as root
if [[ $EUID -eq 0 ]]; then
   echo "❌ This script should not be run as root for security reasons"
   echo "Please run as: bash deploy-app.sh"
   exit 1
fi

# Check if app directory exists
if [ ! -d "$APP_DIR" ]; then
    echo "📁 Creating application directory..."
    sudo mkdir -p $APP_DIR
    sudo chown -R $APP_USER:$APP_USER $APP_DIR
fi

# Extract application
echo "📦 Extracting application..."
cd $APP_DIR
if [ -f "aishou.tar" ]; then
    tar -xf aishou.tar
    mv aishou/* .
    rmdir aishou
    rm aishou.tar
    echo "✅ Application extracted successfully"
else
    echo "❌ aishou.tar not found in $APP_DIR"
    echo "Please upload the distribution file first"
    exit 1
fi

# Make gradlew executable
chmod +x $APP_DIR/bin/aishou

# Check if .env file exists
if [ ! -f "$APP_DIR/.env" ]; then
    echo "⚠️ Creating sample .env file..."
    cat > $APP_DIR/.env << 'EOL'
JWT_SECRET=your-super-secret-jwt-key-here-change-this
MONGO_URI=mongodb+srv://username:password@cluster.mongodb.net/aishou?retryWrites=true&w=majority
ONESIGNAL_APP_ID=your-onesignal-app-id
ONESIGNAL_REST_KEY=your-onesignal-rest-key
PUBLIC_BASE_URL=https://api.yourdomain.com
OPENAI_API_KEY=your-openai-api-key
EOL
    echo "❗ IMPORTANT: Please edit $APP_DIR/.env with your actual values!"
fi

# Create systemd service file
echo "🔧 Creating systemd service..."
sudo tee /etc/systemd/system/$SERVICE_NAME.service > /dev/null << EOL
[Unit]
Description=Aishou Backend Service
After=network.target
Wants=network.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_DIR
ExecStart=$APP_DIR/bin/aishou
EnvironmentFile=$APP_DIR/.env
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

# JVM tuning for t3.micro
Environment="JAVA_OPTS=-Xmx400m -Xms200m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -server"

# Security settings
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=$APP_DIR

[Install]
WantedBy=multi-user.target
EOL

# Set correct permissions
sudo chown -R $APP_USER:$APP_USER $APP_DIR
sudo chmod 600 $APP_DIR/.env

# Reload systemd and start service
echo "🔄 Starting application service..."
sudo systemctl daemon-reload
sudo systemctl enable $SERVICE_NAME
sudo systemctl start $SERVICE_NAME

# Wait a moment and check status
sleep 5
if sudo systemctl is-active --quiet $SERVICE_NAME; then
    echo "✅ Application started successfully!"
    echo "🌐 Application is running on port 8080"
    echo "📊 Check status: sudo systemctl status $SERVICE_NAME"
    echo "📋 View logs: sudo journalctl -u $SERVICE_NAME -f"
else
    echo "❌ Application failed to start!"
    echo "📋 Check logs: sudo journalctl -u $SERVICE_NAME --lines=20"
    exit 1
fi

echo "🎉 Deployment completed successfully!"
echo ""
echo "📝 Next steps:"
echo "1. Configure Nginx reverse proxy"
echo "2. Set up SSL certificate"
echo "3. Test API endpoints"
echo "4. Set up monitoring"