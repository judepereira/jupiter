ALTER TABLE inbound_mcp_credentials DROP COLUMN token_ciphertext;
ALTER TABLE inbound_mcp_credentials DROP COLUMN rotated_at;
DELETE FROM app_settings WHERE setting_key = 'inbound_mcp_initialized';
