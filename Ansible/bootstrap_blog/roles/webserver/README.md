# 📘 Bootstrap Blog with Ansible

This Ansible role automates the deployment of a simple Nginx-hosted blog using customizable variables for server IP, blog name, and blog content.

---

## 📦 Features

✔ Installs and starts Nginx  
✔ Creates a custom blog directory  
✔ Deploys blog HTML files using templates  
✔ Configures and enables a custom Nginx site  
✔ Disables the default Nginx site  

---

## 📁 Role Structure

```
.
├── playbook.yml
├── inventory/
│   └── hosts
└── roles/
    └── webserver/
        ├── tasks/
        │   └── main.yml
        ├── handlers/
        │   └── main.yml
        └── templates/
            ├── index.html.j2
            ├── about.html.j2
            └── nginx.conf.j2
```

---

## ⚙️ Variables

Defined in your playbook or `group_vars`/`host_vars`:

```yaml
blog_dir: /var/www/blog
blog_page:
  - index.html
  - about.html
blog_name: My Awesome Blog
server_name: 
web_root: /var/www/blog
```

---

## 🧰 Role Tasks Summary

```yaml
- Install nginx and update cache
- Start nginx service
- Create blog directory (e.g. /var/www/blog)
- Deploy template-based blog pages (index.html, about.html)
- Configure Nginx using a Jinja2 template
- Enable the blog site via symlink
- Disable the default site to avoid conflict
```

### Handler

```yaml
- name: restart nginx
  service:
    name: nginx
    state: restarted
    enabled: true
```

---

## 🧪 How to Run

Make sure your `inventory/hosts` file is configured, then run:

```bash
ansible-playbook -i inventory/hosts playbook.yml --ask-become-pass
```

---


## 📝 Notes

- Ensure that port 80 is open and not in use by another service (e.g., Apache).
- Templates like `index.html.j2`, `about.html.j2`, and `nginx.conf.j2` should be placed in the `templates/` directory.
- The Nginx config must match your `blog_dir` and `server_name` settings.