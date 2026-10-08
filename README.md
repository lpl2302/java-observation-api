# Programming 3 Assignment

## Student Number
`2409897`

## Features Included In This Submission

Minimum features:
- Feature 1: **User can store observation information as a record**
- Feature 2: **User can get all observations from the server**
- Feature 3: **User can register and authenticate**
- Feature 4: **User can attach observatory information to the observation data**

Additional features:
- Feature 5: **User can attach observatory weather to observations**
- Feature 6: **The endpoint collections must be implemented with the required behaviour**
- Feature 7: **Observations stored on the server can be updated**
- Feature 8: **Server can accept partial observations and complete them using an external GraphQL server**

## Instructions

- Build: `mvn clean package`
- Run server: `java -jar target/server-1.0-jar-with-dependencies.jar <keystore-path> <keystore-password>`
- HTTPS API endpoint: `https://localhost:8001`
- Registration endpoint: `POST /registration` (no authentication required)
- Authenticated endpoints: `/datarecord`, `/datarecord/partial`, `/collections`
- Database path can be configured with `DATABASE_PATH` (default: `database.db`)
- GraphQL conversion endpoint can be configured with `GRAPHQL_URL` (default: `http://localhost:4003/graphql`)
- Weather endpoint can be configured with `WEATHER_URL` (default: `http://localhost:4001/wfs`)
- The server writes `server_output.json` for the CI persistence check.
