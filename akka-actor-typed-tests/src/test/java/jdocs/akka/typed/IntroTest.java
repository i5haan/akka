/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

// #imports

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.Props;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.javadsl.Behaviors;

// #imports

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class IntroTest {

  // #hello-world-actor
  public abstract static class HelloWorld {
    // no instances of this class, it's only a name space for messages
    // and static methods
    private HelloWorld() {}

    public static final class Greet {
      public final String whom;
      public final ActorRef<Greeted> replyTo;

      public Greet(String whom, ActorRef<Greeted> replyTo) {
        this.whom = whom;
        this.replyTo = replyTo;
      }
    }

    public static final class Greeted {
      public final String whom;
      public final ActorRef<Greet> from;

      public Greeted(String whom, ActorRef<Greet> from) {
        this.whom = whom;
        this.from = from;
      }
    }

    public static final Behavior<Greet> greeter =
        Behaviors.receive(
            (context, message) -> {
              context.getLog().info("Hello {}!", message.whom);
              message.replyTo.tell(new Greeted(message.whom, context.getSelf()));
              return Behaviors.same();
            });
  }
  // #hello-world-actor

  // #hello-world-bot
  public abstract static class HelloWorldBot {
    private HelloWorldBot() {}

    public static final Behavior<HelloWorld.Greeted> bot(int greetingCounter, int max) {
      return Behaviors.receive(
          (context, message) -> {
            int n = greetingCounter + 1;
            context.getLog().info("Greeting {} for {}", n, message.whom);
            if (n == max) {
              return Behaviors.stopped();
            } else {
              message.from.tell(new HelloWorld.Greet(message.whom, context.getSelf()));
              return bot(n, max);
            }
          });
    }
  }
  // #hello-world-bot

  // #hello-world-main
  public abstract static class HelloWorldMain {
    private HelloWorldMain() {}

    public static class Start {
      public final String name;

      public Start(String name) {
        this.name = name;
      }
    }

    public static final Behavior<Start> main =
        Behaviors.setup(
            context -> {
              final ActorRef<HelloWorld.Greet> greeter =
                  context.spawn(HelloWorld.greeter, "greeter");

              return Behaviors.receiveMessage(
                  message -> {
                    ActorRef<HelloWorld.Greeted> replyTo =
                        context.spawn(HelloWorldBot.bot(0, 3), message.name);
                    greeter.tell(new HelloWorld.Greet(message.name, replyTo));
                    return Behaviors.same();
                  });
            });
  }
  // #hello-world-main

  public abstract static class CustomDispatchersExample {
    private CustomDispatchersExample() {}

    public static class Start {
      public final String name;

      public Start(String name) {
        this.name = name;
      }
    }

    // #hello-world-main-with-dispatchers
    public static final Behavior<Start> main =
        Behaviors.setup(
            context -> {
              final String dispatcherPath = "akka.actor.default-blocking-io-dispatcher";

              Props props = DispatcherSelector.fromConfig(dispatcherPath);
              final ActorRef<HelloWorld.Greet> greeter =
                  context.spawn(HelloWorld.greeter, "greeter", props);

              return Behaviors.receiveMessage(
                  message -> {
                    ActorRef<HelloWorld.Greeted> replyTo =
                        context.spawn(HelloWorldBot.bot(0, 3), message.name);
                    greeter.tell(new HelloWorld.Greet(message.name, replyTo));
                    return Behaviors.same();
                  });
            });
    // #hello-world-main-with-dispatchers
  }

  public static void main(String[] args) throws Exception {
    // #hello-world
    final ActorSystem<HelloWorldMain.Start> system =
        ActorSystem.create(HelloWorldMain.main, "hello");

    system.tell(new HelloWorldMain.Start("World"));
    system.tell(new HelloWorldMain.Start("Akka"));
    // #hello-world

    Thread.sleep(3000);
    system.terminate();
  }

  // #chatroom-actor
  public static class ChatRoom {
    // #chatroom-protocol
    static interface RoomCommand {}

    public static final class GetSession implements RoomCommand {
      public final String screenName;
      public final ActorRef<SessionEvent> replyTo;

      public GetSession(String screenName, ActorRef<SessionEvent> replyTo) {
        this.screenName = screenName;
        this.replyTo = replyTo;
      }
    }
    // #chatroom-protocol
    // #chatroom-behavior
    private static final class PublishSessionMessage implements RoomCommand {
      public final String screenName;
      public final String message;

      public PublishSessionMessage(String screenName, String message) {
        this.screenName = screenName;
        this.message = message;
      }
    }
    // #chatroom-behavior
    // #chatroom-protocol

    static interface SessionEvent {}

    public static final class SessionGranted implements SessionEvent {
      public final ActorRef<PostMessage> handle;

      public SessionGranted(ActorRef<PostMessage> handle) {
        this.handle = handle;
      }
    }

    public static final class SessionDenied implements SessionEvent {
      public final String reason;

      public SessionDenied(String reason) {
        this.reason = reason;
      }
    }

    public static final class MessagePosted implements SessionEvent {
      public final String screenName;
      public final String message;

      public MessagePosted(String screenName, String message) {
        this.screenName = screenName;
        this.message = message;
      }
    }

    static interface SessionCommand {}

    public static final class PostMessage implements SessionCommand {
      public final String message;

      public PostMessage(String message) {
        this.message = message;
      }
    }

    private static final class NotifyClient implements SessionCommand {
      final MessagePosted message;

      NotifyClient(MessagePosted message) {
        this.message = message;
      }
    }
    // #chatroom-protocol
    // #chatroom-behavior

    public static Behavior<RoomCommand> behavior() {
      return chatRoom(new ArrayList<ActorRef<SessionCommand>>());
    }

    private static Behavior<RoomCommand> chatRoom(List<ActorRef<SessionCommand>> sessions) {
      return Behaviors.receive(RoomCommand.class)
          .onMessage(
              GetSession.class,
              (context, getSession) -> {
                ActorRef<SessionEvent> client = getSession.replyTo;
                ActorRef<SessionCommand> ses =
                    context.spawn(
                        session(context.getSelf(), getSession.screenName, client),
                        URLEncoder.encode(getSession.screenName, StandardCharsets.UTF_8.name()));
                // narrow to only expose PostMessage
                client.tell(new SessionGranted(ses.narrow()));
                List<ActorRef<SessionCommand>> newSessions = new ArrayList<>(sessions);
                newSessions.add(ses);
                return chatRoom(newSessions);
              })
          .onMessage(
              PublishSessionMessage.class,
              (context, pub) -> {
                NotifyClient notification =
                    new NotifyClient((new MessagePosted(pub.screenName, pub.message)));
                sessions.forEach(s -> s.tell(notification));
                return Behaviors.same();
              })
          .build();
    }

    public static Behavior<ChatRoom.SessionCommand> session(
        ActorRef<RoomCommand> room, String screenName, ActorRef<SessionEvent> client) {
      return Behaviors.receive(ChatRoom.SessionCommand.class)
          .onMessage(
              PostMessage.class,
              (context, post) -> {
                // from client, publish to others via the room
                room.tell(new PublishSessionMessage(screenName, post.message));
                return Behaviors.same();
              })
          .onMessage(
              NotifyClient.class,
              (context, notification) -> {
                // published from the room
                client.tell(notification.message);
                return Behaviors.same();
              })
          .build();
    }
    // #chatroom-behavior

  }
  // #chatroom-actor

  // #chatroom-gabbler
  public abstract static class Gabbler {
    private Gabbler() {}

    public static Behavior<ChatRoom.SessionEvent> behavior() {
      return Behaviors.receive(ChatRoom.SessionEvent.class)
          .onMessage(
              ChatRoom.SessionDenied.class,
              (context, message) -> {
                System.out.println("cannot start chat room session: " + message.reason);
                return Behaviors.stopped();
              })
          .onMessage(
              ChatRoom.SessionGranted.class,
              (context, message) -> {
                message.handle.tell(new ChatRoom.PostMessage("Hello World!"));
                return Behaviors.same();
              })
          .onMessage(
              ChatRoom.MessagePosted.class,
              (context, message) -> {
                System.out.println(
                    "message has been posted by '" + message.screenName + "': " + message.message);
                return Behaviors.stopped();
              })
          .build();
    }
  }
  // #chatroom-gabbler

  public static void runChatRoom() throws Exception {

    // #chatroom-main
    Behavior<Void> main =
        Behaviors.setup(
            context -> {
              ActorRef<ChatRoom.RoomCommand> chatRoom =
                  context.spawn(ChatRoom.behavior(), "chatRoom");
              ActorRef<ChatRoom.SessionEvent> gabbler =
                  context.spawn(Gabbler.behavior(), "gabbler");
              context.watch(gabbler);
              chatRoom.tell(new ChatRoom.GetSession("ol’ Gabbler", gabbler));

              return Behaviors.<Void>receiveSignal(
                  (c, sig) -> {
                    if (sig instanceof Terminated) return Behaviors.stopped();
                    else return Behaviors.unhandled();
                  });
            });

    final ActorSystem<Void> system = ActorSystem.create(main, "ChatRoomDemo");
    // #chatroom-main
  }
}
