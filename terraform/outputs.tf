output "instance_public_ip" {
  value = aws_instance.users_app.public_ip
}

output "instance_public_dns" {
  value = aws_instance.users_app.public_dns
}
