#!/bin/bash

# CloudWatch Monitoring Setup Script
# Usage: sudo bash setup-monitoring.sh

set -e

echo "📊 Setting up CloudWatch monitoring..."

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo "❌ This script must be run as root"
   echo "Please run as: sudo bash setup-monitoring.sh"
   exit 1
fi

# Create CloudWatch agent configuration directory
echo "📁 Creating CloudWatch configuration..."
mkdir -p /opt/aws/amazon-cloudwatch-agent/etc/

# Copy CloudWatch agent configuration
cp cloudwatch-agent.json /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json

# Set correct permissions
chown -R cwagent:cwagent /opt/aws/amazon-cloudwatch-agent/

# Start CloudWatch agent
echo "🚀 Starting CloudWatch agent..."
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
    -a fetch-config \
    -m ec2 \
    -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json \
    -s

# Enable CloudWatch agent service
systemctl enable amazon-cloudwatch-agent

# Check status
if systemctl is-active --quiet amazon-cloudwatch-agent; then
    echo "✅ CloudWatch agent is running!"
    echo "📊 Metrics will be available in AWS CloudWatch console"
    echo "📋 Logs will be available in CloudWatch Logs"
else
    echo "❌ CloudWatch agent failed to start!"
    systemctl status amazon-cloudwatch-agent
    exit 1
fi

echo "📈 Setting up log rotation..."
cat > /etc/logrotate.d/aishou << 'EOL'
/var/log/nginx/aishou_*.log {
    daily
    missingok
    rotate 30
    compress
    delaycompress
    notifempty
    create 644 nginx nginx
    postrotate
        systemctl reload nginx > /dev/null 2>&1 || true
    endscript
}
EOL

echo "🎉 Monitoring setup completed successfully!"
echo ""
echo "📊 CloudWatch Features Enabled:"
echo "- CPU, Memory, Disk metrics"
echo "- Network and system metrics"
echo "- Nginx access and error logs"
echo "- Systemd service logs"
echo ""
echo "📝 Next steps:"
echo "1. Check metrics in AWS CloudWatch console"
echo "2. Set up alarms for critical metrics"
echo "3. Create CloudWatch dashboard"