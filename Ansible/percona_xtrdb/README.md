
# Ansible Role: Percona XtraDB Cluster (PXC) Installation & Configuration

## Overview

This Ansible role automates the installation, configuration, and bootstrap of a **Percona XtraDB Cluster 8.0** on Debian-based Linux systems. It handles:


- Installing required dependencies.
- Adding and configuring the Percona repository.
- Installing Percona XtraDB Cluster packages.
- Setting up firewall rules with UFW.
- Deploying a customized MySQL configuration tailored for PXC.
- Bootstrapping the first cluster node.
- Starting and restarting MySQL service on all nodes.

---

## Role Structure

- **roles/install/tasks/main.yml**  
  Installs packages, adds Percona repo, installs PXC, and configures UFW firewall.

- **roles/config/tasks/main.yml**  
  Backs up existing MySQL config and deploys a custom `mysqld.cnf` template for PXC.

- **roles/bootstrap/tasks/main.yml**  
  Bootstraps the first PXC node, waits for MySQL socket, and restarts MySQL on all nodes.

- **templates/mysqld.cnf.j2**  
  Jinja2 template for the MySQL configuration file, parameterized with cluster variables.

---

## Usage

### Variables to Define

| Variable             | Description                                 |
|----------------------|---------------------------------------------|
| `server_id`          | Unique server ID per MySQL node (integer). |
| `cluster_name`       | Name of the Percona cluster (string).       |
| `wsrep_cluster_nodes`| Comma-separated addresses of all cluster nodes. |
| `wsrep_node_address` | IP address of the current node.              |
| `wsrep_node_name`    | Logical name for the current node.           |

Example inventory or playbook snippet:

```yaml
pxc:
  hosts:
    pxc-node1:
      server_id: 1
      wsrep_node_address: 10.0.0.1
      wsrep_node_name: pxc-node1
    pxc-node2:
      server_id: 2
      wsrep_node_address: 10.0.0.2
      wsrep_node_name: pxc-node2

cluster_name: my_cluster
wsrep_cluster_nodes: "gcomm://10.0.0.1,10.0.0.2"
```

### Running the Role

Include the roles in your playbook as:

```yaml
- hosts: pxc
  become: true
  roles:
    - install
    - config
    - bootstrap
```

---

## What This Role Does

### Install Role

- Updates apt package cache.
- Installs dependencies: wget, gnupg2, lsb-release, curl.
- Downloads and installs Percona APT repository package.
- Configures Percona repository for PXC 8.0.
- Installs `percona-xtradb-cluster` package.
- Installs UFW firewall.
- Opens required ports in UFW:
  - 3306 TCP (MySQL client)
  - 4444 TCP (SST)
  - 4567 TCP/UDP (Cluster replication)
  - 4568 TCP (IST)
  - 9200 TCP (Optional, possibly for monitoring)
- Enables UFW.

### Config Role

- Backs up existing `/etc/mysql/mysql.conf.d/mysqld.cnf` (if any).
- Deploys the `mysqld.cnf` template configured for PXC clustering with all necessary `wsrep` and InnoDB settings.

### Bootstrap Role

- Stops MySQL on the first cluster node.
- Bootstraps the Percona cluster on the first node by setting the `PXC_BOOTSTRAP` environment variable.
- Waits for MySQL socket to appear on the first node.
- Restarts MySQL on all other cluster nodes to join the cluster.
- Enables MySQL service on all nodes.

---

## Notes

- The first node in the `pxc` group inventory is automatically considered the bootstrap node.
- Make sure all variables like IP addresses, node names, and server IDs are correctly defined.
- The role assumes Debian/Ubuntu-based OS and systemd service manager.
- The UFW configuration opens cluster communication ports necessary for PXC operation.
- The `mysqld.cnf.j2` template can be customized further to fit specific environment requirements.

---

