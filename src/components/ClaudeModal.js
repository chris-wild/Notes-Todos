import React from 'react';

export default function ClaudeModal({ onClose }) {
  return (
    <div className="admin-overlay" onClick={onClose}>
      <div className="admin-panel about-panel" onClick={(e) => e.stopPropagation()}>
        <div className="admin-panel-header">
          <h2>Claude</h2>
          <button className="close-viewer-btn" onClick={onClose}>Close</button>
        </div>

        <div className="about-content">
          <p>
            Claude is an AI assistant made by{' '}
            <a href="https://www.anthropic.com" target="_blank" rel="noopener noreferrer">Anthropic</a>.
            It co-authored this app — writing the code, designing the security model, and iterating on
            features alongside Chris.
          </p>
          <p>
            <a href="https://platform.claude.com" target="_blank" rel="noopener noreferrer">
              platform.claude.com
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
