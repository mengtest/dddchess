package com.javacook.dddchess.rest;

import akka.actor.ActorSystem;
import akka.dispatch.OnComplete;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.AskTimeoutException;
import com.javacook.dddchess.api.ChessGameApi;
import com.javacook.dddchess.domain.FigureValueObject;
import com.javacook.dddchess.domain.MoveException;
import com.javacook.dddchess.domain.MoveValueObject;
import com.javacook.dddchess.domain.PositionValueObject;
import com.javacook.dddchess.domain.PositionValueObject.HorCoord;
import com.javacook.dddchess.domain.PositionValueObject.VertCoord;
import com.webcohesion.enunciate.metadata.rs.ResponseCode;
import com.webcohesion.enunciate.metadata.rs.StatusCodes;
import org.glassfish.jersey.server.ManagedAsync;
import scala.concurrent.Future;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.Optional;


@Path("/chessgame")
public class RestService {

    @Context
    ActorSystem actorSystem;

    @Context
    ChessGameApi chessGameApi;

    @Context
    UriInfo uriInfo;

    LoggingAdapter log;


    @PostConstruct
    void initialize() {
        log = Logging.getLogger(actorSystem, this);
    }


    /**
     * Checks whether the application is alive
     * @return the current date and time
     */
    @GET
    @Path("isalive")
    @Produces(MediaType.TEXT_PLAIN)
    public String figureAt() {
        log.info("dddchess is alive");
        return "DDD-Chess is alive: " + new Date();
    }


    /**
     * Returns the figure at the position (<code>horCoord</code>, <code>vertCord</code>)
     * @param horCoord the horizontal coordinate of field on the chess board
     * @param vertCoord the vertical coordinate of field on the chess board
     * @return the chess figure at the given coordinates
     */
    @GET
    @Path("board")
    @Produces(MediaType.APPLICATION_JSON)
    @StatusCodes({
            @ResponseCode( code = 200, condition = "ok"),
            @ResponseCode( code = 404, condition = "The field at the given coordinates is empty"),
            @ResponseCode( code = 500, condition = "An exception occured")
    })
    public FigureValueObject figureAt(
            @NotNull @QueryParam("horCoord") HorCoord horCoord,
            @NotNull @QueryParam("vertCoord") VertCoord vertCoord) {

        log.info("Get figure at horCoord={}, vertCoord={}", horCoord, vertCoord);

        final PositionValueObject position = new PositionValueObject(horCoord, vertCoord);
        final Optional<FigureValueObject> figure = chessGameApi.figureAt(position);

        if (figure.isPresent()) {
            return figure.get();
        }
        else {
            throw new NotFoundException("There is no figure at " + position);
        }
    }


    @GET
    @Path("move/{index}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    public MoveValueObject getMove(@NotNull @PathParam("index") int index) {
        log.info("Get the {}. move", index);
        final Optional<MoveValueObject> move = chessGameApi.getMove(index);
        if (move.isPresent()) {
            return move.get();
        }
        else {
            throw new NotFoundException("There is no move present for index " + index);
        }
    }


    @POST
    @Path("move")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @ManagedAsync
    public void postMove(@FormParam("move") String move,
                         @Suspended final AsyncResponse resp) {

        if (move == null) {
            throw new BadRequestException("Missing form parameter 'move'");
        }
        postMove(new MoveValueObject(move), resp);
    }


    @POST
    @Path("move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ManagedAsync
    public void postMove(MoveValueObject move,
                         @Suspended final AsyncResponse resp) {

        log.info("Try to perform the performMove {}", move);

        // API-Call:
        final Future<Object> future = chessGameApi.performMove(move);

        future.onComplete(new OnComplete<Object>() {

            public void onComplete(Throwable failure, Object result) {
                if (failure == null) {
                    log.info("Move index: " + result);
                    HashMap<String, Object> json = new HashMap<>();
                    json.put("index", result);
                    UriBuilder ub = uriInfo.getAbsolutePathBuilder();
                    URI location = ub.path(result.toString()).build();
                    resp.resume(Response.created(location).entity(json).build());
                }
                else {
                    log.error(failure, failure.getMessage());
                    HashMap<String, Object> json = new HashMap<>();
                    if (failure instanceof AskTimeoutException) {
                        json.put(ErrorCode.ERROR_CODE_KEY, ErrorCode.TIMEOUT);
                        resp.resume(Response.status(503).entity(json).build());
                    }
                    else if (failure instanceof MoveException) {
                        json.put(ErrorCode.ERROR_CODE_KEY, ErrorCode.INVALID_MOVE);
                        json.put(ErrorCode.INVALID_MOVE.name(), failure.getMessage());
                        resp.resume(Response.status(422).entity(json).build());
                    }
                    else {
                        resp.resume(Response.serverError().entity(failure).build());
                    }
                }
            }
        }, actorSystem.dispatcher());
    }// postMove

}
