# Instructions for AI Agents

## Guidelines
1. When writing code, do not write silent fallback behaviour. When something fails, it must fail loud. 
   Render the error message to the user.
2. Follow the Java beans naming conventions. Method names are in lowerCamelCase. Boolean variables are not prefixed with `is`.
3. Use Lombok's annotations extensively where possible.
4. Do not write comments for code that is self-explanatory.
5. Do not unnecessarily qualify class names. Avoid static imports for variables and methods, other than junit assertion methods.

## Build Instructions
1. Source .env from the project root – this contains the necessary environment 
variables that allow the project to be built and tested.
2. Compile using `./mvnw compile`
3. Run tests using `./mvnw test`
