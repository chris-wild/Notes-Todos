import React, { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import AboutModal from './AboutModal';
import ClaudeModal from './ClaudeModal';

export default function AuthPage() {
  const { handleLogin, handleRegister, authError, setAuthError } = useAuth();

  const [showRegister, setShowRegister] = useState(false);
  const [showAbout, setShowAbout] = useState(false);
  const [showClaudeProfile, setShowClaudeProfile] = useState(false);

  const [loginUsername, setLoginUsername] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [registerUsername, setRegisterUsername] = useState('');
  const [registerPassword, setRegisterPassword] = useState('');
  const [registerConfirm, setRegisterConfirm] = useState('');

  const onLogin = async (e) => {
    e.preventDefault();
    const ok = await handleLogin(loginUsername, loginPassword);
    if (ok) {
      setLoginUsername('');
      setLoginPassword('');
    }
  };

  const onRegister = async (e) => {
    e.preventDefault();
    const ok = await handleRegister(registerUsername, registerPassword, registerConfirm);
    if (ok) {
      setRegisterUsername('');
      setRegisterPassword('');
      setRegisterConfirm('');
    }
  };

  if (showRegister) {
    return (
      <div className="login-container">
        <div className="login-box">
          <div className="login-header-row">
            <h1>Create Account</h1>
            <button type="button" className="keep-icon-btn" onClick={() => setShowAbout(true)}>
              About
            </button>
          </div>
          <form onSubmit={onRegister}>
            <input
              type="text"
              placeholder="Username (min 3 characters)"
              value={registerUsername}
              onChange={(e) => setRegisterUsername(e.target.value)}
              autoFocus
            />
            <input
              type="password"
              placeholder="Password (min 6 characters)"
              value={registerPassword}
              onChange={(e) => setRegisterPassword(e.target.value)}
            />
            <input
              type="password"
              placeholder="Confirm password"
              value={registerConfirm}
              onChange={(e) => setRegisterConfirm(e.target.value)}
            />
            {authError && <div className="auth-error">{authError}</div>}
            <button type="submit">Register</button>
          </form>
          <p className="auth-switch">
            Already have an account?{' '}
            <button type="button" onClick={() => { setShowRegister(false); setAuthError(''); }}>
              Login
            </button>
          </p>
        </div>

        {showAbout && (
          <AboutModal
            onClose={() => setShowAbout(false)}
            onOpenClaude={() => { setShowAbout(false); setShowClaudeProfile(true); }}
          />
        )}
        {showClaudeProfile && (
          <ClaudeModal onClose={() => setShowClaudeProfile(false)} />
        )}
      </div>
    );
  }

  return (
    <div className="login-container">
      <div className="login-box">
        <div className="login-header-row">
          <h1>Notes &amp; Todos</h1>
          <button type="button" className="keep-icon-btn" onClick={() => setShowAbout(true)}>
            About
          </button>
        </div>
        <form onSubmit={onLogin}>
          <input
            type="text"
            placeholder="Username"
            value={loginUsername}
            onChange={(e) => setLoginUsername(e.target.value)}
            autoFocus
          />
          <input
            type="password"
            placeholder="Password"
            value={loginPassword}
            onChange={(e) => setLoginPassword(e.target.value)}
          />
          {authError && <div className="auth-error">{authError}</div>}
          <button type="submit">Login</button>
        </form>
        <p className="auth-switch">
          Don't have an account?{' '}
          <button type="button" onClick={() => { setShowRegister(true); setAuthError(''); }}>
            Register
          </button>
        </p>
      </div>

      {showAbout && (
        <AboutModal
          onClose={() => setShowAbout(false)}
          onOpenClaude={() => { setShowAbout(false); setShowClaudeProfile(true); }}
        />
      )}
      {showClaudeProfile && (
        <ClaudeModal onClose={() => setShowClaudeProfile(false)} />
      )}
    </div>
  );
}
