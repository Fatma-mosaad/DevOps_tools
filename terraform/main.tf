terraform {
  required_version = ">= 1.5.0"

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

# ============================================================
# Security Group
# ============================================================

resource "aws_security_group" "users_app" {
  name        = "users-app-sg"
  description = "Security group for users application server"

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
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"

    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "users-app-sg"
  }
}

# ============================================================
# EC2 Instance
# ============================================================

resource "aws_instance" "users_app" {
  ami           = var.ami_id
  instance_type = var.instance_type
  key_name      = var.key_name

  vpc_security_group_ids = [
    aws_security_group.users_app.id
  ]

  tags = {
    Name = "users-app-server"
  }
}
