terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

resource "aws_instance" "users_app" {
  ami           = var.ami_id
  instance_type = var.instance_type

  key_name = var.key_name

  vpc_security_group_ids = [aws_security_group.users_app.id]

  user_data = <<-EOF
            #!/bin/bash
            set -e

            # Log all bootstrap output
            exec > >(tee /var/log/users-app-bootstrap.log | logger -t users-app-bootstrap -s 2>/dev/console) 2>&1

            echo "========================================="
            echo "Starting EC2 bootstrap"
            echo "========================================="

            # Update packages
            echo "Updating system packages..."
            dnf update -y

            # Install Docker
            # NOTE: don't install "curl" here — Amazon Linux 2023 already ships
            # "curl-minimal" which conflicts with the full "curl" package and
            # causes the whole dnf install to fail (including docker).
            # curl-minimal already provides everything this script needs.
            echo "Installing Docker..."
            dnf install -y docker

            # Enable and start Docker
            echo "Enabling Docker service..."
            systemctl enable docker

            echo "Starting Docker service..."
            systemctl start docker

            # Wait for Docker to become ready, and fail loudly if it never does
            echo "Waiting for Docker..."
            DOCKER_READY=0
            for i in {1..30}; do
              if systemctl is-active --quiet docker; then
                echo "Docker service is running."
                DOCKER_READY=1
                break
              fi

              echo "Docker is not ready yet. Attempt $i/30..."
              sleep 2
            done

            if [ "$DOCKER_READY" != "1" ]; then
              echo "ERROR: Docker service never became active. Aborting bootstrap."
              exit 1
            fi

            # Add ec2-user to docker group
            echo "Adding ec2-user to docker group..."
            usermod -aG docker ec2-user

            # Install Docker Compose plugin
            echo "Installing Docker Compose plugin..."

            mkdir -p /usr/local/lib/docker/cli-plugins

            curl -fL --retry 5 --retry-delay 5 \
              https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
              -o /usr/local/lib/docker/cli-plugins/docker-compose

            chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

            # Verify Docker
            echo "========================================="
            echo "Verifying Docker installation"
            echo "========================================="

            docker --version

            # Verify Docker Compose
            echo "========================================="
            echo "Verifying Docker Compose installation"
            echo "========================================="

            docker compose version

            echo "========================================="
            echo "EC2 bootstrap completed successfully"
            echo "========================================="
            EOF

  tags = {
    Name = "users-app-server"
  }
}

resource "aws_security_group" "users_app" {
  name = "users-app-sg"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.ssh_cidr
  }

  ingress {
    description = "Flask Application"
    from_port   = 5000
    to_port     = 5000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
