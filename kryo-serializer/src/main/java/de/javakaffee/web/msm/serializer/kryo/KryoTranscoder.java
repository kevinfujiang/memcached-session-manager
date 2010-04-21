/*
 * Copyright 2010 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm.serializer.kryo;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ObjectBuffer;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serialize.BigDecimalSerializer;
import com.esotericsoftware.kryo.serialize.BigIntegerSerializer;

import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.ClassSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyListSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyMapSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptySetSerializer;
import de.javakaffee.kryoserializers.CopyForIterateCollectionSerializer;
import de.javakaffee.kryoserializers.CopyForIterateMapSerializer;
import de.javakaffee.kryoserializers.CurrencySerializer;
import de.javakaffee.kryoserializers.GregorianCalendarSerializer;
import de.javakaffee.kryoserializers.JdkProxySerializer;
import de.javakaffee.kryoserializers.KryoReflectionFactorySupport;
import de.javakaffee.kryoserializers.StringBufferSerializer;
import de.javakaffee.kryoserializers.StringBuilderSerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.SessionTranscoder;

/**
 * A {@link SessionAttributesTranscoder} that uses {@link Kryo} for serialization.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class KryoTranscoder extends SessionTranscoder implements SessionAttributesTranscoder {

    private static final Log LOG = LogFactory.getLog( KryoTranscoder.class );
    
    private final Kryo _kryo;
    private final SerializerFactory[] _serializerFactories;

    /**
     * 
     */
    public KryoTranscoder() {
        this( null, null, false );
    }
    
    /**
     * @param classLoader
     * @param copyCollectionsForSerialization 
     * @param customConverterClassNames 
     */
    public KryoTranscoder( final ClassLoader classLoader, final String[] customConverterClassNames, final boolean copyCollectionsForSerialization ) {
        final Pair<Kryo, SerializerFactory[]> pair = createKryo( classLoader, customConverterClassNames, copyCollectionsForSerialization );
        _kryo = pair.a;
        _serializerFactories = pair.b;
    }

    private Pair<Kryo, SerializerFactory[]> createKryo( final ClassLoader classLoader,
            final String[] customConverterClassNames, final boolean copyCollectionsForSerialization ) {
        
        final Kryo kryo = new KryoReflectionFactorySupport() {
            
            @Override
            @SuppressWarnings( "unchecked" )
            public Serializer newSerializer(final Class clazz) {
                final Serializer customSerializer = loadCustomSerializer( clazz );
                if ( customSerializer != null ) {
                    return customSerializer;
                }
                if ( copyCollectionsForSerialization ) {
                    final Serializer copyCollectionSerializer = loadCopyCollectionSerializer( clazz );
                    if ( copyCollectionSerializer != null ) {
                        return copyCollectionSerializer;
                    }
                }
                return super.newSerializer( clazz );
            }
            
        };
        
        if ( classLoader != null ) {
            kryo.setClassLoader( classLoader );
        }
        
        // com.esotericsoftware.minlog.Log.TRACE = true;
        
        kryo.setRegistrationOptional( true );
        kryo.register( Arrays.asList( "" ).getClass(), new ArraysAsListSerializer( kryo ) );
        kryo.register( Currency.class, new CurrencySerializer( kryo ) );
        kryo.register( StringBuffer.class, new StringBufferSerializer( kryo ) );
        kryo.register( StringBuilder.class, new StringBuilderSerializer( kryo ) );
        kryo.register( Collections.EMPTY_LIST.getClass(), new CollectionsEmptyListSerializer() );
        kryo.register( Collections.EMPTY_MAP.getClass(), new CollectionsEmptyMapSerializer() );
        kryo.register( Collections.EMPTY_SET.getClass(), new CollectionsEmptySetSerializer() );
        kryo.register( Class.class, new ClassSerializer( kryo ) );
        kryo.register( BigDecimal.class, new BigDecimalSerializer() );
        kryo.register( BigInteger.class, new BigIntegerSerializer() );
        kryo.register( GregorianCalendar.class, new GregorianCalendarSerializer() );
        kryo.register( InvocationHandler.class, new JdkProxySerializer( kryo ) );
        UnmodifiableCollectionsSerializer.registerSerializers( kryo );
        SynchronizedCollectionsSerializer.registerSerializers( kryo );
        
        final Pair<KryoCustomization[], SerializerFactory[]> pair = loadCustomConverter( customConverterClassNames,
                classLoader, kryo );
        
        final KryoCustomization[] customizations = pair.a;
        if ( customizations != null ) {
            for( final KryoCustomization customization : customizations ) {
                try {
                    LOG.info( "Executing KryoCustomization " + customization.getClass().getName() );
                    customization.customize( kryo );
                } catch( final Throwable e ) {
                    LOG.error( "Could not execute customization " + customization, e );
                }
            }
        }
        
        return Pair.create( kryo, pair.b );
    }
    
    private Serializer loadCustomSerializer( final Class<?> clazz ) {
        if ( _serializerFactories != null ) {
            for( int i = 0; i < _serializerFactories.length; i++ ) {
                final Serializer serializer = _serializerFactories[i].newSerializer( clazz );
                if ( serializer != null ) {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug( "Loading custom serializer " + serializer.getClass().getName() + " for class " + clazz );
                    }
                    return serializer;
                }
            }
        }
        return null;
    }
    
    private Serializer loadCopyCollectionSerializer( final Class<?> clazz ) {
        if ( Collection.class.isAssignableFrom( clazz ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Loading CopyForIterateCollectionSerializer for class " + clazz );
            }
            return new CopyForIterateCollectionSerializer( _kryo );
        }
        if ( Map.class.isAssignableFrom( clazz ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Loading CopyForIterateMapSerializer for class " + clazz );
            }
            return new CopyForIterateMapSerializer( _kryo );
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings( "unchecked" )
    @Override
    public Map<String, Object> deserializeAttributes( final byte[] data ) {
        return new ObjectBuffer( _kryo ).readObject( data, ConcurrentHashMap.class );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        /**
         * Creates an ObjectStream with an initial buffer size of 50KB and a maximum size of 1000KB.
         */
        return new ObjectBuffer( _kryo, 50 * 1024, 1000 * 1024 ).writeObject( attributes );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected MemcachedBackupSession deserialize( final byte[] in ) {
        throw new UnsupportedOperationException( "Session deserialization not implemented." );
    }

    private Pair<KryoCustomization[], SerializerFactory[]> loadCustomConverter( final String[] customConverterClassNames, final ClassLoader classLoader,
            final Kryo kryo ) {
        if ( customConverterClassNames == null || customConverterClassNames.length == 0 ) {
            return Pair.empty();
        }
        final List<KryoCustomization> customizations = new ArrayList<KryoCustomization>();
        final List<SerializerFactory> serializerFactories = new ArrayList<SerializerFactory>();
        final ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        for ( int i = 0; i < customConverterClassNames.length; i++ ) {
            final String element = customConverterClassNames[i];
            try {
                processElement( element, customizations, serializerFactories, kryo, loader );
            } catch ( final Exception e ) {
                LOG.error( "Could not instantiate " + element + ", omitting this KryoCustomization/SerializerFactory.", e );
                throw new RuntimeException( "Could not load serializer " + element, e );
            }
        }
        final KryoCustomization[] customizationsArray = customizations.toArray( new KryoCustomization[customizations.size()] );
        final SerializerFactory[] serializerFactoriesArray = serializerFactories.toArray( new SerializerFactory[serializerFactories.size()] );
        return Pair.create( customizationsArray, serializerFactoriesArray );
    }

    private void processElement( final String element, final List<KryoCustomization> customizations,
            final List<SerializerFactory> serializerFactories, final Kryo kryo, final ClassLoader loader )
        throws ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException,
        InvocationTargetException {
        final Class<?> clazz = Class.forName( element, true, loader );
        if ( KryoCustomization.class.isAssignableFrom( clazz ) ) {
            LOG.info( "Loading KryoCustomization " + element );
            final KryoCustomization customization = createInstance( clazz.asSubclass( KryoCustomization.class ), kryo );
            customizations.add( customization );
            if ( customization instanceof SerializerFactory ) {
                serializerFactories.add( (SerializerFactory) customization );
            }
        }
        else if ( SerializerFactory.class.isAssignableFrom( clazz ) ) {
            LOG.info( "Loading SerializerFactory " + element );
            final SerializerFactory factory = createInstance( clazz.asSubclass( SerializerFactory.class ), kryo );
            serializerFactories.add( factory );
        }
        else  {
            throw new IllegalArgumentException( "The provided class '" + element + "'" +
            		" does not implement SerializerFactory or KryoCustomization." +
            		" Implemented interfaces: " +Arrays.asList( clazz.getInterfaces() ) );
        }
    }

    private static <T> T createInstance( final Class<? extends T> clazz, final Kryo kryo ) throws SecurityException, NoSuchMethodException, IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
        try {
            final Constructor<? extends T> constructor = clazz.getConstructor( Kryo.class );
            return constructor.newInstance( kryo );
        } catch ( final NoSuchMethodException nsme ) {
            final Constructor<? extends T> constructor = clazz.getConstructor();
            return constructor.newInstance();
        }
    }
    
    private static class Pair<A,B> {
        private static final Pair<?, ?> EMPTY = Pair.create( null, null );
        private final A a;
        private final B b;
        public Pair( final A a, final B b ) {
            this.a = a;
            this.b = b;
        }
        public static <A, B> Pair<A, B> create( final A a, final B b ) {
            return new Pair<A, B>( a, b );
        }
        @SuppressWarnings( "unchecked" )
        public static <A, B> Pair<A, B> empty() {
            return (Pair<A, B>) EMPTY;
        }
    }

}