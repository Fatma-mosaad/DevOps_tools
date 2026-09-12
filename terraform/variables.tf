variable "aws_region" {
  type        = string
  description = "AWS region"

  default = "eu-central-1"
}

variable "ami_id" {
  type        = string
  description = "Linux AMI ID"
}

variable "instance_type" {
  type        = string
  description = "EC2 instance type"

  default = "t3.micro"
}

variable "key_name" {
  type        = string
  description = "Existing EC2 key pair name"
}

variable "ssh_cidr" {
  type        = list(string)
  description = "CIDR ranges allowed to access SSH"
}
