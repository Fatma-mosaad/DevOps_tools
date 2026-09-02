variable "aws_region" {
  type        = string
  description = "AWS region"
  default     = "eu-central-1"
}

variable "ami_id" {
  type        = string
  description = "Ubuntu AMI ID"
}

variable "instance_type" {
  type        = string
  default     = "t3.micro"
}

variable "key_name" {
  type        = string
  description = "AWS EC2 key pair name"
}

variable "ssh_cidr" {
  type        = string
  description = "CIDR allowed to access SSH"
}
