FROM node:22-alpine
WORKDIR /app
COPY server.mjs index.html workflow.html app.js styles.css ./
ENV FRONTEND_HOST=0.0.0.0 FRONTEND_PORT=4173
EXPOSE 4173
USER node
CMD ["node", "server.mjs"]
