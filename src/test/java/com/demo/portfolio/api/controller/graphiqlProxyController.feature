Feature: GraphiQL Proxy Controller

  # Tests the /proxy/graphiql endpoint end-to-end.
  #
  # Flow for each "through proxy" scenario:
  #   1. GET /proxy/graphiql?role=<role> → server looks up credentials, fetches /graphiql
  #      server-side, and returns HTML with an injected <script> that embeds the
  #      Authorization header.
  #   2. Extract that header from the HTML using a JavaScript regex.
  #   3. POST /model with the extracted header and a GraphQL payload, exactly as
  #      the browser-side GraphiQL UI would do.
  #
  # This validates the full proxy chain without any credentials appearing in a URL.

  Background:
    * url baseUrl
    # JavaScript helper: extracts the 'Basic ...' value injected by the proxy into GraphiQL HTML.
    # Uses Java String.indexOf/substring (via Karate JS-Java interop) rather than regex to
    # avoid Karate feature-file parsing issues with single-quoted regex literals.
    * def extractAuth =
      """
      function(html) {
        var s = new java.lang.String('' + html);
        var marker = "'Authorization': '";
        var idx = s.indexOf(marker);
        if (idx < 0) return null;
        var start = idx + marker.length();
        var end = s.indexOf("'", start);
        return end < 0 ? null : s.substring(start, end);
      }
      """

  # ── Proxy basic behaviour ──────────────────────────────────────────────────

  Scenario: Proxy returns 200 HTML with injected script for admin role
    Given path '/proxy/graphiql'
    And param role = 'admin'
    When method get
    Then status 200
    And match header Content-Type contains 'text/html'
    And match response contains 'window.fetch'
    And match response contains 'Authorization'

  Scenario: Proxy returns 400 for an unknown role
    Given path '/proxy/graphiql'
    And param role = 'superadmin'
    When method get
    Then status 400

  # ── Successful queries through proxy-injected credentials ─────────────────

  Scenario: Admin can list customers through proxy-injected credentials
    # Step 1 – get HTML from the proxy and extract the injected Authorization header
    Given path '/proxy/graphiql'
    And param role = 'admin'
    When method get
    Then status 200
    * def proxyAuth = extractAuth(response)
    * assert proxyAuth != null

    # Step 2 – use the extracted header to call the GraphQL endpoint
    Given path basePath
    And header Authorization = proxyAuth
    And def query = read('classpath:com/demo/portfolio/api/fetcher/GetCustomers.graphql')
    And def variables = { page: 0, size: 5, orderStatus: null, orderPage: 0, orderSize: 5 }
    And request { query: '#(query)', variables: '#(variables)' }
    When method post
    Then status 200
    And match response.data.customers != null
    And match response.data.customers.content == '#[]'
    And match each response.data.customers.content ==
    """
    {
      id: '#string',
      firstName: '#string',
      lastName: '#string',
      email: '#string',
      orders: '#[]'
    }
    """

  Scenario: Reader can query a single customer through proxy-injected credentials
    Given path '/proxy/graphiql'
    And param role = 'reader'
    When method get
    Then status 200
    * def proxyAuth = extractAuth(response)
    * assert proxyAuth != null

    Given path basePath
    And header Authorization = proxyAuth
    And def query = read('classpath:com/demo/portfolio/api/fetcher/GetCustomer.graphql')
    And def variables = { id: '4', orderStatus: null, orderPage: 0, orderSize: 10 }
    And request { query: '#(query)', variables: '#(variables)' }
    When method post
    Then status 200
    And match response.data.customer.id == '#string'
    And match response.data.customer.firstName == '#string'
    And match response.data.customer.lastName == '#string'
    And match response.data.customer.email == '#string'

  Scenario: Reader can query orders through proxy-injected credentials
    Given path '/proxy/graphiql'
    And param role = 'reader'
    When method get
    Then status 200
    * def proxyAuth = extractAuth(response)
    * assert proxyAuth != null

    Given path basePath
    And header Authorization = proxyAuth
    And def query = read('classpath:com/demo/portfolio/api/fetcher/GetOrdersByCustomerAndStatus.graphql')
    And def variables = { customerId: '1', status: 'DELIVERED', page: 0, size: 10 }
    And request { query: '#(query)', variables: '#(variables)' }
    When method post
    Then status 200
    And match response.data.orders.content == '#[]'
    And match each response.data.orders.content[*].status == variables.status
    And match each response.data.orders.content[*].customer.id == variables.customerId

  Scenario: Writer can create an order through proxy-injected credentials
    Given path '/proxy/graphiql'
    And param role = 'writer'
    When method get
    Then status 200
    * def proxyAuth = extractAuth(response)
    * assert proxyAuth != null

    Given path basePath
    And header Authorization = proxyAuth
    And def query = read('classpath:com/demo/portfolio/api/fetcher/CreateOrder.graphql')
    And def variables = { input: { customerId: '1', totalAmount: 49.99 } }
    And request { query: '#(query)', variables: '#(variables)' }
    When method post
    Then status 200
    And match response.data.createOrder.id == '#string'
    And match response.data.createOrder.totalAmount == variables.input.totalAmount
    And match response.data.createOrder.status == 'PENDING'

  # ── Access-denied scenarios through proxy-injected credentials ─────────────

  Scenario: Reader credentials from proxy cannot create a customer (Forbidden)
    # ROLE_READER is read-only; createCustomer requires ROLE_WRITER or higher.
    # Even though the credentials come from the proxy, the GraphQL endpoint still enforces
    # method-level security and returns HTTP 200 with a GraphQL errors array.
    Given path '/proxy/graphiql'
    And param role = 'reader'
    When method get
    Then status 200
    * def proxyAuth = extractAuth(response)

    Given path basePath
    And header Authorization = proxyAuth
    And def query = read('classpath:com/demo/portfolio/api/fetcher/CreateCustomer.graphql')
    And def variables = { input: { firstName: 'Blocked', lastName: 'Reader', email: 'blocked@example.com' } }
    And request { query: '#(query)', variables: '#(variables)' }
    When method post
    Then status 200
    And match response.errors != null
    And match response.errors[0].message == 'Forbidden'
    And match response.data.createCustomer == '#notpresent'

  Scenario: Writer credentials from proxy cannot delete a customer (Forbidden)
    # ROLE_WRITER can create/update but not delete; deleteCustomer requires ROLE_ADMIN.
    # Even though the credentials come from the proxy, the GraphQL endpoint still enforces
    # method-level security and returns HTTP 200 with a GraphQL errors array.
    Given path '/proxy/graphiql'
    And param role = 'writer'
    When method get
    Then status 200
    * def proxyAuth = extractAuth(response)

    Given path basePath
    And header Authorization = proxyAuth
    And def query = read('classpath:com/demo/portfolio/api/fetcher/DeleteCustomer.graphql')
    And def variables = { id: '5' }
    And request { query: '#(query)', variables: '#(variables)' }
    When method post
    Then status 200
    And match response.errors != null
    And match response.errors[0].message == 'Forbidden'
    And match response.data.deleteCustomer == '#notpresent'
