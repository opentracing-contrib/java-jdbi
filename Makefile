build:
	./mvnw -s settings.xml install

publish:
	./mvnw -s settings.xml clean deploy
