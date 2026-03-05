import React, { createContext, useContext, useState, useCallback } from 'react';

const AuthContext = createContext(null);

// API base URL.
// - In dev, CRA proxy (package.json "proxy") allows using relative paths ("/api").
// - For non-local access set REACT_APP_API_URL to e.g. "http://192.168.x.x:3001/api".
const API_URL = (process.env.REACT_APP_API_URL && process.env.REACT_APP_API_URL.trim()) || '/api';

const host = typeof window !== 'undefined' ? window.location.hostname : '';
const inferredInstance = (host === 'localhost' || host === '127.0.0.1') ? 'dev' : 'prod';
const instance = process.env.REACT_APP_INSTANCE || inferredInstance;

export function AuthProvider({ children }) {
  const [token, setToken] = useState(localStorage.getItem('token'));
  const [username, setUsername] = useState(localStorage.getItem('username'));
  const [authError, setAuthError] = useState('');

  const handleLogout = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    setToken(null);
    setUsername(null);
  }, []);

  const authFetch = useCallback(async (url, options = {}) => {
    const headers = {
      'Content-Type': 'application/json',
      ...options.headers,
    };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    try {
      const res = await fetch(url, { ...options, headers });
      if (res.status === 401) {
        handleLogout();
        return null;
      }
      return res;
    } catch (err) {
      return null;
    }
  }, [token, handleLogout]);

  const handleLogin = useCallback(async (loginUsername, loginPassword) => {
    setAuthError('');
    try {
      const res = await fetch(`${API_URL}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: loginUsername, password: loginPassword })
      });
      const data = await res.json();
      if (res.ok) {
        localStorage.setItem('token', data.token);
        localStorage.setItem('username', data.username);
        setToken(data.token);
        setUsername(data.username);
        return true;
      } else {
        setAuthError(data.error || 'Login failed');
        return false;
      }
    } catch (err) {
      setAuthError('Error connecting to server');
      return false;
    }
  }, []);

  const handleRegister = useCallback(async (registerUsername, registerPassword, registerConfirm) => {
    setAuthError('');
    if (registerPassword !== registerConfirm) {
      setAuthError('Passwords do not match');
      return false;
    }
    try {
      const res = await fetch(`${API_URL}/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: registerUsername, password: registerPassword })
      });
      const data = await res.json();
      if (res.ok) {
        localStorage.setItem('token', data.token);
        localStorage.setItem('username', data.username);
        setToken(data.token);
        setUsername(data.username);
        return true;
      } else {
        setAuthError(data.error || 'Registration failed');
        return false;
      }
    } catch (err) {
      setAuthError('Error connecting to server');
      return false;
    }
  }, []);

  return (
    <AuthContext.Provider value={{
      token,
      username,
      authFetch,
      handleLogout,
      handleLogin,
      handleRegister,
      authError,
      setAuthError,
      API_URL,
      instance,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
