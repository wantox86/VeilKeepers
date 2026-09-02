CREATE TABLE IF NOT EXISTS attachments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    vault_item_id BIGINT UNSIGNED NOT NULL,
    encrypted_filename VARBINARY(255) NOT NULL,
    mime_type VARCHAR(32) NOT NULL,
    size BIGINT UNSIGNED NOT NULL,
    storage_path VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_attachments_user_item (user_id, vault_item_id),
    CONSTRAINT fk_attachments_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_attachments_item FOREIGN KEY (vault_item_id) REFERENCES vault_items(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
