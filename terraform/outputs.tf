output "instance_id" {
  description = "Created EC2 instance ID"

  value = aws_instance.users_app.id
}

output "instance_public_ip" {
  description = "Public IP of the EC2 instance"

  value = aws_instance.users_app.public_ip
}

output "instance_public_dns" {
  description = "Public DNS of the EC2 instance"

  value = aws_instance.users_app.public_dns
}
