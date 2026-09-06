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

            dnf update -y
            dnf install -y docker

            systemctl enable docker
            systemctl start docker

            usermod -aG docker ec2-user

            mkdir -p /usr/local/lib/docker/cli-plugins

            curl -SL \
              https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
              -o /usr/local/lib/docker/cli-plugins/docker-compose

            chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
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
