FROM p8/java-maven
ADD . /app
WORKDIR /app
RUN mvn package