# PlayerSync

Plugin de Paper 1.21.x que sincroniza los datos de los jugadores entre los servidores de
una red.

Un jugador que sale de un servidor y entra en otro llega con su inventario, su estado, su
experiencia, sus efectos, sus estadísticas y sus logros exactamente como los dejó.

## Requisitos

Java 21, Paper 1.21.x, MongoDB 4.4+ y Redis (obligatorio detrás de un proxy).

## Setup

Copia el jar en `plugins/`, arranca una vez para generar el `config.yml`, rellena la
conexión y el server-id, y reinicia.

```yaml
storage:
  mongo:
    uri: "mongodb://usuario:contraseña@host:27017/?authSource=admin"
    database: playersync
    collection: players

sync:
  server-id: "lobby-1"   # único por servidor: de él depende el bloqueo de sesión
```

Para probarlo en local, `docker compose up -d` levanta MongoDB (27017), Redis (6379) y
mongo-express (8081) ya configurados con los mismos valores que el `config.yml` por
defecto, así que el plugin conecta sin tocar nada.

## Qué se sincroniza

| Bloque           | Contenido                                                                                                                               |
| ---              |-----------------------------------------------------------------------------------------------------------------------------------------|
| **Perfil**       | UUID, último nombre, primer login, última conexión, último servidor                                                                     |
| **Estado**       | Vida y vida máxima, comida, saturación, agotamiento, experiencia, modo de juego, vuelo, velocidades, fuego, distancia de caída, efectos |
| **Ubicaciones**  | Última ubicación, ubicación por mundo, punto de reaparición                                                                             |
| **Inventario**   | Inventario principal, off hand, armadura, slot seleccionado, ender chest                                                                |
| **Estadísticas** | Lista configurable de estadísticas                                                                                                      |
| **Logros**       | Criterios conseguidos de cada logro, las recetas siempre excluidas                                                                      |

## Bloqueo de sesión

Cada documento lleva un subdocumento `lock` que indica qué servidor lo retiene. Eso es lo
que impide que un jugador entre en un segundo servidor con un inventario desactualizado y
duplique sus objetos. El bloqueo es un lease, así que un servidor que se cae no deja a
nadie atrapado.

Redis es obligatorio detrás de un proxy porque el proxy conecta al jugador al servidor
destino mientras el de origen todavía lo tiene, y ninguno de los dos puede avanzar hasta
que a uno se le pide que suelte.

### Protecciones

- **Un bloqueo no deja atrapado a un jugador.** Es una renta, no un candado: pasado
  `lease-duration-ms` sin renovarse, cualquier servidor puede tomarlo, así que un servidor
  que se cae suelta solo todo lo que retenía. Si un traspaso por el proxy empieza y luego
  falla, el de origen ve que el jugador sigue ahí y recupera la propiedad.
- **Los datos viejos nunca pisan a los buenos.** Cada escritura está condicionada a que
  el servidor siga reteniendo el bloqueo, así que si una escritura llega tarde, después de que 
  el jugador ya se ha movido a otro sitio, ésta no guardará nada.
- **A un jugador nunca se le deja entrar sin sus datos.** Si no se puede tomar el bloqueo a
  tiempo, se rechaza la entrada. Entrar con el inventario vacío sería peor: el siguiente
  guardado sobreescribiría el vacío encima de los datos reales.
- **Una caída de MongoDB no pierde sesiones.** La escritura que importa es la final, la de
  un jugador que se desconecta, así que esas se retienen en una cola y se
  reintentan hasta que MongoDB vuelve.
- **Sin picos de lag.** El guardado periódico no captura a todos los conectados a la vez, sino que
  corre cada segundo y toma solo la porción que toca, cubriendo a todos exactamente una vez
  por intervalo. Los leases se renuevan tres veces por duración, así que un pico de lag
  aislado no los pierde.

## Extras

- **Bloqueo de sesión** con lease, más un handshake por Redis para traspasos instantáneos por proxy, ver más abajo.
- **Ender chest**, punto de reaparición y location  por mundo.
- **Comando de administración** `/playersync info | save | unlock`, con permisos por subcomando.
- **Eventos de API** para otros plugins: `PlayerDataLoadEvent`, `PlayerDataApplyEvent`, `PlayerDataSaveEvent` (snapshot mutable, guardados cancelables).
- **Cola de reintentos** para escrituras que fallan con MongoDB caído; escritura de apagado con reintentos.
- **Auto-guardado escalonado** repartido por el intervalo, sin picos de lag.
- **Toggles por field** en `config.yml`, hasta cada pieza del inventario.
- **`docker compose up -d`** con un entorno local listo para usar.
- **Versión de esquema** en cada documento para futuras migraciones.
