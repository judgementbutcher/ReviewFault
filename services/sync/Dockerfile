FROM mcr.microsoft.com/dotnet/sdk:10.0-alpine AS build
WORKDIR /src
COPY services/sync/ReviewFault.Sync.csproj services/sync/
RUN dotnet restore services/sync/ReviewFault.Sync.csproj
COPY services/sync services/sync
RUN dotnet publish services/sync/ReviewFault.Sync.csproj -c Release -o /out --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0-alpine
RUN addgroup -S reviewfault && adduser -S reviewfault -G reviewfault
WORKDIR /app
COPY --from=build /out .
USER reviewfault
ENV ASPNETCORE_URLS=http://+:8080
EXPOSE 8080
ENTRYPOINT ["dotnet", "ReviewFault.Sync.dll"]
