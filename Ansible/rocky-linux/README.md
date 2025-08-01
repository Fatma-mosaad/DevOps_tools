
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

- Provisions a VM on Proxmox with custom configuration
- Configures network and basic system utilities from Rocky Linux DVD
- Creates users and injects SSH public keys
- Prepares VM for SSH and network access
- Stops and converts VM into a reusable Proxmox template

## Roles Overview

### create_vm

- Creates a new virtual machine on the Proxmox node
- Starts the VM
- Can be extended to:
  - Upload Rocky Linux ISO
  - Attach virtual disks
  - Modify hardware parameters

### config_vm

This role configures the Rocky Linux VM after creation:

🔹 Mounts Rocky Linux DVD:

🔹 Installs `dnf` manually (if needed):

🔹 Sets up local YUM repo:

🔹 Installs basic tools:

🔹 Reloads systemd:

🔹 Configures networking:
- Ensures `/etc/NetworkManager/system-connections` exists
- Adds a keyfile config for `eth0` with DHCP
- Restarts NetworkManager if available

🔹 Creates users with SSH access:

This prepares the system to be accessed remotely and used as a base template.

### convert_template

- Shuts down the VM (forcefully if needed)
- Converts the VM into a Proxmox template
- Allows future VMs to be cloned from this image easily

## Usage

ansible-playbook -i inventory/hosts playbook.yaml
