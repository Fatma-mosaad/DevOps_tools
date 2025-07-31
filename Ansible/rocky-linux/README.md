
#  Rocky Linux Proxmox Provisioning with Ansible

This project automates the provisioning of a Rocky Linux virtual machine on a Proxmox hypervisor using Ansible. It configures the VM, injects SSH keys, and optionally converts the VM into a reusable Proxmox template.

## Project Structure

```
.
├── group_vars/
│   └── all.yml              # Global variables: Proxmox credentials, VM config, etc.
├── inventory/
│   └── hosts                # Ansible inventory with the proxmox host
├── roles/
│   ├── create_vm/           # Role to create the VM
│   ├── config_vm/           # Role to configure the VM (users, SSH keys, etc.)
│   └── convert_template/    # Role to shutdown and convert VM to template
├── playbook.yaml            # Main playbook that calls all roles
└── README.md               
```

## Features

-  Provisions a VM on Proxmox with custom configuration
-  Creates users and adds their SSH public keys
-  Stops and waits for VM shutdown before template conversion
-  Converts the VM to a Proxmox template for future cloning

## Roles Overview

### create_vm
- Creates and starts a new VM
- Can be extended to download ISO, attach disks, etc.

### config_vm
- Creates Linux users
- Injects SSH keys
- Prepares system for templating

### convert_template
- Stops the VM using force shutdown (no QEMU agent required)
- Converts VM into a Proxmox template





