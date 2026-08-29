CREATE TABLE IF NOT EXISTS devices (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    device_identifier VARCHAR(128) NOT NULL DEFAULT '',
    device_name VARCHAR(128) NOT NULL DEFAULT '',
    last_seen_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_devices_user_identifier (user_id, device_identifier),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
