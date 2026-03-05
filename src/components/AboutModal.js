import React from 'react';

export default function AboutModal({ onClose, onOpenClaude }) {
  return (
    <div className="admin-overlay" onClick={onClose}>
      <div className="admin-panel about-panel" onClick={(e) => e.stopPropagation()}>
        <div className="admin-panel-header">
          <h2>About</h2>
          <button className="close-viewer-btn" onClick={onClose}>Close</button>
        </div>

        <div className="about-content">
          <p>
            A small, fast notes + todos app built for real day-to-day use. Opinionated where it matters
            (speed, dark mode, keyboard-first workflows) and deliberately simple everywhere else.
          </p>

          <h3>Who built it</h3>
          <p>
            Co-authored by Chris and{' '}
            <button type="button" className="claude-link" onClick={onOpenClaude}>Claude</button>
            {' '}(Anthropic). Chris sets the direction and shapes the product; Claude writes the code, and they collaboratively handle security hardening
            and keeping the engineering tight.
          </p>

          <h3>How it's built</h3>
          <ul>
            <li><strong>Frontend:</strong> React (single-page app)</li>
            <li><strong>Backend:</strong> Node.js + Express</li>
            <li><strong>Database:</strong> Postgres</li>
            <li><strong>Auth:</strong> JWT for the web app; API keys for programmatic access</li>
            <li><strong>Hosting:</strong> Docker on AWS ECS behind a load balancer</li>
          </ul>

          <h3>Security</h3>
          <p>
            Passwords hashed with Argon2id. API keys encrypted at rest (AES-256-GCM).
            Semgrep (SAST) and OWASP ZAP (DAST) scanning integrated into the deploy pipeline.
          </p>

          <h3>What to expect</h3>
          <ul>
            <li>Simple UI, minimal clutter</li>
            <li>Features added when they earn their keep</li>
            <li>Pragmatic trade-offs: reliability over shiny</li>
          </ul>

          <div className="about-footer">
            <div className="about-signoff">
              Built with care by{' '}
              <a href="https://www.linkedin.com/in/chriswild/" target="_blank" rel="noopener noreferrer">
                Chris
              </a>{' '}
              +{' '}
              <button type="button" className="claude-link" onClick={onOpenClaude}>
                Claude
              </button>
              .
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
