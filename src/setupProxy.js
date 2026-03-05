const { createProxyMiddleware } = require('http-proxy-middleware');

// CRA's package.json "proxy" behaves differently for navigation requests that
// accept text/html (it may serve index.html instead of proxying).
// We *always* want /api (and /uploads) to hit the backend.
module.exports = function (app) {
  app.use(
    ['/api', '/uploads'],
    createProxyMiddleware({
      target: 'http://localhost:3001',
      changeOrigin: true,
      ws: true,
      logLevel: 'warn'
    })
  );
};
