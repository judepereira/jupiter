This file is to be read and processed by AI agents. It contains coding guidelines, 
and other instructions for working with this project.

## Project Info
1. We run on Java 25 and Java 25 only - no need for backwards compatiblity
2. Java 25 is available under ~/.jdks
3. The project is a Spring boot project, using htmx for the entire frontend. 
   This is on purpose to simplify coding and building.
4. The build process for this project is Maven. Always use the bundled Maven wrapper (./mvnw)

## Principles
1. Follow YAGNI, DRY and KISS

## Coding guidelines
1. No defensive coding - if something can be fully implemented in the backend, 
   there's no need to add handling for it in the frontend.
2. If something fails, it should fail loud - do not add fallbacks for it. Log errors, 
   and remember, we always communicate the error to the user via the frontend
3. Use lombok and Java records wherever possible 
