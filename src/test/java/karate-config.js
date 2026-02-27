function fn() {
  let env = karate.env; // Captura la variable de sistema 'karate.env'
  karate.log('Karate env system property was:', env);
  
  if (!env) {
    env = 'local'; // Entorno por defecto
  }

  const config = {
    baseUrl: 'http://localhost:8080',
    basePath: '/model',
    myToken: 'default-token'
  };

  if (env == 'local') {
    config.baseUrl = 'http://localhost:' + (karate.properties['demo.server.port'] || '8080');
  } else if (env == 'prod') {
    config.baseUrl = 'https://pending.com';
  }

  // HTTP Basic Auth helpers – builds a 'Basic <base64>' header for the given role profile.
  // Reads credentials from API_CREDENTIALS_JSON env var (MUST be Base64 encoded).
  
  const rawCredsBase64 = java.lang.System.getenv('API_CREDENTIALS_JSON');
  
  if (!rawCredsBase64) {
      karate.log('Error: API_CREDENTIALS_JSON environment variable is missing.');
      throw new Error('API_CREDENTIALS_JSON environment variable is required and must be Base64 encoded.');
  }

  let credsJson;
  try {
      const decoder = java.util.Base64.getDecoder();
      const decodedBytes = decoder.decode(rawCredsBase64);
      credsJson = new java.lang.String(decodedBytes);
  } catch (e) {
      karate.log('Error: Failed to decode API_CREDENTIALS_JSON as Base64', e);
      throw e;
  }

  const creds = JSON.parse(credsJson);

  function authHeader(role) {
    const c = creds[role];
    const combined = c.user + ':' + c.pass;
    const bytes = new java.lang.String(combined).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    const token = java.util.Base64.getEncoder().encodeToString(bytes);
    return 'Basic ' + token;
  }

  karate.set('authHeader', authHeader);

  // Tiempo de espera para peticiones (en milisegundos)
  karate.configure('connectTimeout', 5000);
  karate.configure('readTimeout', 5000);
  
  // Imprimir siempre requests y responses para depuración
  karate.configure('logPrettyRequest', true);
  karate.configure('logPrettyResponse', true);

  return config;
}