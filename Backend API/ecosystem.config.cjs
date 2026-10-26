module.exports = {
  apps: [
    {
      name: 'idlix-backend-api',
      script: './dist/server.js',
      instances: 1,
      autorestart: true,
      watch: false,
      max_memory_restart: '800M',
      env: {
        NODE_ENV: 'production',
        PORT: 5376,
        HOST: '0.0.0.0',
        IDLIX_BASE_URL: 'https://z2.idlixku.com',
        LOG_LEVEL: 'info',
        USE_PROXY: process.env.USE_PROXY || 'true',
        PROXY_HOST: process.env.PROXY_HOST || '209.97.175.37',
        PROXY_PORT: process.env.PROXY_PORT || '9050',
      },
    },
  ],
};
