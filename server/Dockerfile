FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3003
COPY package.json ./
RUN npm install --omit=dev
COPY server.js ./
COPY public ./public
EXPOSE 3003
CMD ["node", "server.js"]
