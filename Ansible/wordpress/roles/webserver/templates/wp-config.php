<?php
define('DB_NAME', '{{ wp_db_name }}');
define('DB_USER', '{{ wp_db_user }}');
define('DB_PASSWORD', '{{ wp_db_password }}');
define('DB_HOST', '{{ hostvars["dbserver01"].ansible_host }}');
define('DB_CHARSET', 'utf8');
define('DB_COLLATE', '');

$table_prefix = 'wp_';

define('WP_DEBUG', false);

if ( !defined('ABSPATH') ) {
  define('ABSPATH', __DIR__ . '/');
}

require_once ABSPATH . 'wp-settings.php';
