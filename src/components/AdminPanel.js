import React from 'react';

export default function AdminPanel({
  onClose,
  passwordReset,
  setPasswordReset,
  resetMessage,
  onPasswordReset,
  apiKeys,
  newKeyName,
  setNewKeyName,
  newlyCreatedKey,
  copiedKeyId,
  onCreateKey,
  onDeleteKey,
  onCopyToClipboard,
  anthropicKeyInfo,
  anthropicKeyDraft,
  setAnthropicKeyDraft,
  anthropicKeyMessage,
  anthropicKeySaving,
  onSaveAnthropicKey,
  onDeleteAnthropicKey,
}) {
  return (
    <div className="admin-overlay" onClick={onClose}>
      <div className="admin-panel" onClick={(e) => e.stopPropagation()}>
        <div className="admin-panel-header">
          <h2>Admin</h2>
          <button className="close-viewer-btn" onClick={onClose}>Close</button>
        </div>

        <div className="account-card">
          <h3>Change Password</h3>
          <form onSubmit={onPasswordReset}>
            <input
              type="password"
              placeholder="Current password"
              value={passwordReset.current}
              onChange={(e) => setPasswordReset({ ...passwordReset, current: e.target.value })}
              required
            />
            <input
              type="password"
              placeholder="New password (min 6 characters)"
              value={passwordReset.new}
              onChange={(e) => setPasswordReset({ ...passwordReset, new: e.target.value })}
              required
            />
            <input
              type="password"
              placeholder="Confirm new password"
              value={passwordReset.confirm}
              onChange={(e) => setPasswordReset({ ...passwordReset, confirm: e.target.value })}
              required
            />
            {resetMessage.text && (
              <div className={`reset-message ${resetMessage.type}`}>
                {resetMessage.text}
              </div>
            )}
            <button type="submit" className="save-btn">Update Password</button>
          </form>
        </div>

        <div className="account-card">
          <h3>App API Keys</h3>
          <p className="account-card-desc">
            Create API keys to access your notes and todos via the REST API
            (<code>/api/v1/...</code>). Pass your key as an <code>x-api-key</code> header.
          </p>
          <form onSubmit={onCreateKey}>
            <div className="api-key-form">
              <input
                type="text"
                placeholder="Key name (e.g., 'CLI script')"
                value={newKeyName}
                onChange={(e) => setNewKeyName(e.target.value)}
              />
              <button type="submit" className="save-btn">Generate Key</button>
            </div>
          </form>
          {apiKeys.length === 0 ? (
            <p className="empty-state">No API keys yet.</p>
          ) : (
            <div className="api-key-list">
              {apiKeys.map((key) => (
                <div key={key.id} className="api-key-item">
                  <div className="api-key-info">
                    <div className="api-key-header">
                      <span className="api-key-name">{key.name}</span>
                      <span className="api-key-date">
                        {new Date(key.created_at).toLocaleDateString()}
                      </span>
                    </div>
                    <div className="key-copy-row">
                      <code className="api-key-full">{key.key}</code>
                      <button
                        onClick={() => onCopyToClipboard(key.key, key.id)}
                        className="copy-btn"
                      >
                        {copiedKeyId === key.id ? 'Copied!' : 'Copy'}
                      </button>
                    </div>
                  </div>
                  <button onClick={() => onDeleteKey(key.id)} className="delete-btn">x</button>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="account-card">
          <h3>Anthropic API Key</h3>
          <p className="account-card-desc">
            Paste your Anthropic API key to enable the "Create ingredient list" feature
            on recipes. Get a key at{' '}
            <a href="https://console.anthropic.com/" target="_blank" rel="noreferrer">
              console.anthropic.com
            </a>.
          </p>
          {anthropicKeyInfo.hasKey ? (
            <div className="anthropic-key-active">
              <div className="key-copy-row">
                <code>{anthropicKeyInfo.maskedKey}</code>
                <button
                  type="button"
                  className="delete-btn"
                  onClick={onDeleteAnthropicKey}
                >
                  Remove
                </button>
              </div>
            </div>
          ) : (
            <form onSubmit={onSaveAnthropicKey}>
              <div className="api-key-form">
                <input
                  type="password"
                  placeholder="sk-ant-..."
                  value={anthropicKeyDraft}
                  onChange={(e) => setAnthropicKeyDraft(e.target.value)}
                />
                <button type="submit" className="save-btn" disabled={anthropicKeySaving}>
                  {anthropicKeySaving ? 'Verifying...' : 'Save Key'}
                </button>
              </div>
            </form>
          )}
          {anthropicKeyMessage.text && (
            <div className={`reset-message ${anthropicKeyMessage.type}`}>
              {anthropicKeyMessage.text}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
