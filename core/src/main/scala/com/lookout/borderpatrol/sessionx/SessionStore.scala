package com.lookout.borderpatrol.sessionx

import java.nio.charset.Charset

import com.twitter.bijection.twitter_util.UtilBijections
import com.twitter.io.Buf
import com.twitter.util.{Bijection, Future}
import com.twitter.finagle.memcachedx
import com.twitter.finagle.redis
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import scala.collection.mutable
import scala.util.{Success, Failure}

/**
 * Session store that will store a `Session[_]` data into
 */
trait SessionStore {
  def update[A](session: Session[A])(implicit ev: SessionDataEncoder[A]): Future[Unit]
  def get[A](key: SessionId)(implicit ev: SessionDataEncoder[A]): Future[Option[Session[A]]]
}

/**
 * Default implementations of [[com.lookout.borderpatrol.sessionx.SessionStore SessionStore]] with
 * [[com.twitter.finagle.memcachedx memcachedx]] and an in-memory store for mocking
 */
object SessionStore {

  /**
   * Memcached backend to [[com.lookout.borderpatrol.sessionx.SessionStore SessionStore]]
   *
   * {{{
   *   val store = MemcachedStore(Memcachedx.newKetamaClient("localhost:11211"))
   *   val requestSession = store.get[httpx.Request](id) // default views from `Buf` %> `Request` are included
   *   requestSession.onSuccess(s => log(s"Success! you were going to ${s.data.uri}"))
   *                 .onFailure(log)
   * }}}
   * @param store finagle [[com.twitter.finagle.memcachedx.BaseClient memcachedx.BaseClient]] memcached backend
   */
  case class MemcachedStore(store: memcachedx.BaseClient[Buf])
      extends SessionStore {
    val flag = 0 // ignored flag required by memcached api

    /**
     * Fetches a [[com.lookout.borderpatrol.sessionx.Session Session]] if one exists otherwise `None`. On failure
     * will make a [[com.twitter.util.Future.exception Future.exception]].
     *
     * @param key lookup key
     * @param ev evidence for converting the Buf to the type of A
     * @tparam A [[Session.data]] type that must have a view from `Buf %> Option[A]`
     *
     * @return
     */
    def get[A](key: SessionId)(implicit ev: SessionDataEncoder[A]): Future[Option[Session[A]]] =
      store.get(key.asBase64).flatMap(_ match {
        case None => Future.value(None)
        case Some(buf) => ev.decode(buf) match {
          case Failure(e) => e.toFutureException[Option[Session[A]]]
          case Success(data) => Some(Session(key, data)).toFuture
        }
      })

    /**
     * Stores a [[com.lookout.borderpatrol.sessionx.Session Session]]. On failure returns a
     * [[com.twitter.util.Future.exception Future.exception]]
     *
     * @param session
     * @param ev evidence for the conversion to `Buf`
     * @tparam A [[Session.data]] type that must have a view from `A %> Buf`
     *
     * @return a [[com.twitter.util.Future Future]]
     */
    def update[A](session: Session[A])(implicit ev: SessionDataEncoder[A]): Future[Unit] =
      store.set(session.id.asBase64, flag, session.id.expires, ev.encode(session.data))
  }

  /**
   * Redis backend to [[com.lookout.borderpatrol.sessionx.SessionStore SessionStore]]
   *
   * {{{
   *   //TODO: Fix this description
   *   val store = MemcachedStore(Memcachedx.newKetamaClient("localhost:11211"))
   *   val requestSession = store.get[httpx.Request](id) // default views from `Buf` %> `Request` are included
   *   requestSession.onSuccess(s => log(s"Success! you were going to ${s.data.uri}"))
   *                 .onFailure(log)
   * }}}
   * @param store finagle [[com.twitter.finagle.memcachedx.BaseClient memcachedx.BaseClient]] memcached backend
   */
  case class RedisStore(store: redis.Client)
    extends SessionStore {

    import com.twitter.finagle.netty3.{ChannelBufferBuf => ChannelBuf}

    def buf2ChannelBuffer(buf: Buf): ChannelBuffer =
      ChannelBuf.Owned.extract(buf)

    def channelBuffer2Buf(cb: ChannelBuffer): Buf =
      ChannelBuf.Owned(cb)


    /**
     * Fetches a [[com.lookout.borderpatrol.sessionx.Session Session]] if one exists otherwise `None`. On failure
     * will make a [[com.twitter.util.Future.exception Future.exception]].
     *
     * @param key lookup key
     * @param ev evidence for converting the Buf to the type of A
     * @tparam A [[Session.data]] type that must have a view from `Buf %> Option[A]`
     *
     * @return
     */

    def get[A](key: SessionId)(implicit ev: SessionDataEncoder[A]): Future[Option[Session[A]]] =
      store.get(key.asChannelBuf).flatMap(_ match {
        case None => Future.value(None)
        case Some(cb) => ev.decode(channelBuffer2Buf(cb)) match {
          case Failure(e) => e.toFutureException[Option[Session[A]]]
          case Success(data) => Some(Session(key, data)).toFuture
        }}
      )

    /**
     * Stores a [[com.lookout.borderpatrol.sessionx.Session Session]]. On failure returns a
     * [[com.twitter.util.Future.exception Future.exception]]
     *
     * @param session
     * @param ev evidence for the conversion to `Buf`
     * @tparam A [[Session.data]] type that must have a view from `A %> Buf`
     *
     * @return a [[com.twitter.util.Future Future]]
     */
    def update[A](session: Session[A])(implicit ev: SessionDataEncoder[A]): Future[Unit] =
      store.setEx(session.id.asChannelBuf, SessionId.expiresIn(session.id.expires).inSeconds, buf2ChannelBuffer(ev.encode(session.data)))
  }

  /**
   * An in-memory store for prototyping and testing remote stores
   */
  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.MutableDataStructures"))
  case object InMemoryStore extends SessionStore {

    val store: mutable.Set[Session[Buf]] = mutable.Set[Session[Buf]]()

    def get[A](key: SessionId)(implicit ev: SessionDataEncoder[A]): Future[Option[Session[A]]] =
      store.find(_.id == key) match {
        case Some(s) => ev.decode(s.data) match {
          case Failure(e) => e.toFutureException[Option[Session[A]]]
          case Success(data) => Some(Session(key, data)).toFuture
        }
        case _ => None.toFuture
      }

    def update[A](session: Session[A])(implicit ev: SessionDataEncoder[A]): Future[Unit] =
      if (store.add(session.map(ev.encode))) Future.Unit
      else Future.exception[Unit](new SessionStoreError(s"update failed with $session"))
  }

  /*
  implicit object SessionEncryptor extends Encryptable[PSession, SessionId, Array[Byte]] {
    def apply(a: PSession)(key: SessionId): Array[Byte] =
      CryptKey(key).encrypt[PSession](a)
  }

  implicit object SessionDecryptor extends Decryptable[Array[Byte], SessionId, PSession] {
    def apply(e: Array[Byte])(key: SessionId): PSession =
      CryptKey(key).decryptAs[PSession](e)
  }

  implicit object SessionCryptographer extends SessionCrypto[Array[Byte]]

  case class EncryptedInMemorySessionStore(
        store: mutable.Map[String, Encrypted] = mutable.Map[String, Encrypted]())(
      implicit i2s: SessionId %> String)
      extends EncryptedSessionStore[Encrypted, mutable.Map[String, Encrypted]] {

    def update(key: SessionId)(value: PSession) =
      store.put(i2s(key), value.encrypt).
          fold(Future.exception[Unit](new
              UpdateStoreException(s"Unable to update store with $value")))(_ => Future.value[Unit](()))

    def get(key: SessionId) =
      store.get(i2s(key)).flatMap(decrypt(_)).filterNot(session => session.id.expired).toFuture
  }
  */
}

