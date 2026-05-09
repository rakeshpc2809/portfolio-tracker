import { useMemo } from 'react';
import { Canvas } from '@react-three/fiber';
import { Sphere, OrbitControls, Float, MeshDistortMaterial, Stars, Text, Line } from '@react-three/drei';

interface Allocation {
  name: string;
  weight: number;
  category?: string;
}

function ConnectionLine({ start, end }: { start: [number, number, number], end: [number, number, number] }) {
  return (
    <Line
      points={[start, end]}
      color="#cba6f7"
      lineWidth={0.5}
      transparent
      opacity={0.1}
    />
  );
}

function Node({ alloc, position, idx }: { alloc: Allocation, position: [number, number, number], idx: number }) {
  const radius = Math.max(0.4, (alloc.weight / 100) * 3.5);
  
  return (
    <Float speed={1.5} rotationIntensity={0.5} floatIntensity={0.5} position={position}>
      <Sphere args={[radius, 32, 32]}>
        <MeshDistortMaterial
          color={idx % 2 === 0 ? "#cba6f7" : "#a6e3a1"}
          speed={3}
          distort={0.2}
          roughness={0.1}
          metalness={0.9}
          emissive={idx % 2 === 0 ? "#cba6f7" : "#a6e3a1"}
          emissiveIntensity={0.2}
        />
      </Sphere>
      <Text
        position={[0, radius + 0.5, 0]}
        fontSize={0.2}
        color="white"
        font="https://fonts.gstatic.com/s/raleway/v14/1Ptrg8zYS_SKggPNwK4vaqI.woff"
        anchorX="center"
        anchorY="middle"
      >
        {alloc.name.split(' ')[0]}
      </Text>
    </Float>
  );
}

export default function PortfolioVisualizer({ allocations }: { allocations: Allocation[] }) {
  const nodes = useMemo(() => {
    if (allocations.length === 0) return [];
    
    return allocations.map((alloc, i) => {
      // Spiral/Shell distribution for a more "Matrix" feel
      const phi = Math.acos(-1 + (2 * i) / allocations.length);
      const theta = Math.sqrt(allocations.length * Math.PI) * phi;
      
      const radius = 6;
      return {
        ...alloc,
        pos: [
          radius * Math.cos(theta) * Math.sin(phi),
          radius * Math.sin(theta) * Math.sin(phi),
          radius * Math.cos(phi)
        ] as [number, number, number]
      };
    });
  }, [allocations]);

  return (
    <div className="h-[500px] w-full bg-[#0b0b10] rounded-[3rem] border border-white/5 overflow-hidden shadow-2xl relative group">
      <div className="absolute top-8 left-10 z-10 space-y-1">
        <h4 className="text-[10px] font-black uppercase tracking-[0.4em] text-accent">Spatial Allocation Matrix</h4>
        <p className="text-[9px] text-muted font-bold uppercase tracking-widest opacity-60">Force-Directed Portfolio Topography</p>
      </div>

      <Canvas camera={{ position: [0, 0, 15], fov: 45 }}>
        <color attach="background" args={['#0b0b10']} />
        <Stars radius={100} depth={50} count={5000} factor={4} saturation={0} fade speed={1} />
        
        <ambientLight intensity={0.2} />
        <pointLight position={[10, 10, 10]} intensity={1.5} color="#cba6f7" />
        <pointLight position={[-10, -10, -10]} intensity={1} color="#a6e3a1" />
        
        {nodes.length === 0 ? (
          // Placeholder "Neural Net"
          [...Array(30)].map((_, i) => (
            <Float key={i} speed={4} position={[Math.random() * 10 - 5, Math.random() * 10 - 5, Math.random() * 10 - 5]}>
              <Sphere args={[0.05, 8, 8]}>
                <meshStandardMaterial color="#cba6f7" emissive="#cba6f7" emissiveIntensity={4} />
              </Sphere>
            </Float>
          ))
        ) : (
          <>
            {nodes.map((node, i) => (
              <Node key={node.name} alloc={node} position={node.pos} idx={i} />
            ))}
            {/* Draw connections for adjacent nodes to simulate a network */}
            {nodes.map((node, i) => {
              if (i === 0) return null;
              return <ConnectionLine key={`line-${i}`} start={node.pos} end={nodes[i-1].pos} />;
            })}
          </>
        )}
        
        <OrbitControls 
          autoRotate 
          autoRotateSpeed={0.3} 
          enableZoom={true} 
          minDistance={5} 
          maxDistance={25}
        />
      </Canvas>

      <div className="absolute bottom-8 right-10 z-10 p-6 bg-black/40 backdrop-blur-md rounded-2xl border border-white/5 space-y-4 opacity-0 group-hover:opacity-100 transition-opacity duration-500">
        <div className="flex items-center gap-3">
          <div className="w-2 h-2 rounded-full bg-accent shadow-[0_0_10px_rgba(203,166,247,0.8)]" />
          <span className="text-[8px] font-black text-primary uppercase tracking-widest">Equity Core</span>
        </div>
        <div className="flex items-center gap-3">
          <div className="w-2 h-2 rounded-full bg-buy shadow-[0_0_10px_rgba(166,227,161,0.8)]" />
          <span className="text-[8px] font-black text-primary uppercase tracking-widest">Yield Stability</span>
        </div>
        <p className="text-[7px] text-muted font-bold uppercase tracking-[0.2em] border-t border-white/5 pt-2">
          Scroll to zoom • Drag to rotate
        </p>
      </div>
    </div>
  );
}
