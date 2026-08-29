CREATE TABLE IF NOT EXISTS vault_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    category_id BIGINT UNSIGNED NULL,
    encrypted_payload MEDIUMBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_vault_items_user_category (user_id, category_id),
    KEY idx_vault_items_user_updated (user_id, updated_at),
    CONSTRAINT fk_vault_items_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_vault_items_category FOREIGN KEY (category_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
