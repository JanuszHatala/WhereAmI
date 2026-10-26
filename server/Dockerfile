FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3003

COPY package*.json ./
RUN npm ci --omit=dev

COPY server.js guestNaming.js ./
COPY src ./src
COPY migrations ./migrations
COPY public ./public

USER node

EXPOSE 3003
CMD ["node", "server.js"]
