import { useRef, useState } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { OrbitControls, Html } from '@react-three/drei';
import * as THREE from 'three';

interface SchemeItem {
  schemeName: string;
  simpleName?: string;
  allocationPercentage: number;
  hmmState?: string;
  xirr?: string;
}

interface AllocationSphereProps {
  schemes: SchemeItem[];
  onFundClick: (schemeName: string) => void;
  isPrivate: boolean;
}

function SphereNode({
  scheme,
  position,
  onFundClick,
  isPrivate,
}: {
  scheme: SchemeItem;
  position: [number, number, number];
  onFundClick: (schemeName: string) => void;
  isPrivate: boolean;
}) {
  const meshRef = useRef<THREE.Mesh>(null);
  const [hovered, setHovered] = useState(false);

  // Map HMM state to color
  const getColor = (state?: string) => {
    switch (state?.toUpperCase()) {
      case 'CALM_BULL':
        return '#a6e3a1'; // Green
      case 'VOLATILE_BEAR':
        return '#f38ba8'; // Red
      case 'STRESSED_NEUTRAL':
        return '#fab387'; // Peach
      default:
        return '#89b4fa'; // Blue
    }
  };

  // Base size calculated from allocation percentage (min size 0.2, max 1.5)
  const size = Math.max(0.2, Math.min(1.5, (scheme.allocationPercentage / 100) * 4));

  useFrame((state) => {
    if (meshRef.current) {
      // Gentle floating animation
      const time = state.clock.getElapsedTime();
      meshRef.current.position.y = position[1] + Math.sin(time + position[0]) * 0.1;
      
      // Rotate node
      meshRef.current.rotation.x = time * 0.2;
      meshRef.current.rotation.y = time * 0.1;
    }
  });

  return (
    <mesh
      ref={meshRef}
      position={position}
      onClick={() => onFundClick(scheme.schemeName)}
      onPointerOver={(e) => {
        e.stopPropagation();
        setHovered(true);
        document.body.style.cursor = 'pointer';
      }}
      onPointerOut={() => {
        setHovered(false);
        document.body.style.cursor = 'auto';
      }}
    >
      <sphereGeometry args={[size, 32, 32]} />
      <meshPhysicalMaterial
        color={getColor(scheme.hmmState)}
        emissive={getColor(scheme.hmmState)}
        emissiveIntensity={hovered ? 0.4 : 0.1}
        roughness={0.1}
        metalness={0.1}
        clearcoat={1.0}
        clearcoatRoughness={0.1}
        transmission={0.3}
        thickness={0.5}
      />

      {hovered && (
        <Html distanceFactor={15} center>
          <div className="bg-[#181825]/95 backdrop-blur-md border border-white/10 p-4 rounded-xl shadow-2xl text-xs space-y-1.5 pointer-events-none min-w-[160px] select-none text-[#cdd6f4]">
            <p className="font-black text-[10px] text-accent uppercase tracking-wider truncate max-w-[150px]">
              {scheme.simpleName || scheme.schemeName}
            </p>
            <div className="flex justify-between border-t border-white/5 pt-1.5">
              <span className="text-[9px] font-bold uppercase tracking-widest text-muted">Weight</span>
              <span className="font-bold tabular-nums text-primary">{scheme.allocationPercentage.toFixed(2)}%</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[9px] font-bold uppercase tracking-widest text-muted">XIRR</span>
              <span className={`font-bold tabular-nums ${parseFloat(scheme.xirr || '0') >= 0 ? 'text-buy' : 'text-exit'}`}>
                {isPrivate ? '••••' : scheme.xirr || '0%'}
              </span>
            </div>
          </div>
        </Html>
      )}
    </mesh>
  );
}

function OrbitingGroup({
  schemes,
  onFundClick,
  isPrivate,
}: {
  schemes: SchemeItem[];
  onFundClick: (schemeName: string) => void;
  isPrivate: boolean;
}) {
  const groupRef = useRef<THREE.Group>(null);

  // Distribute active holdings around a sphere using spherical Fibonacci grid
  const activeHoldings = schemes.filter((s) => s.allocationPercentage > 0.01);
  const count = activeHoldings.length;
  const radius = 6.5;

  const nodes = activeHoldings.map((scheme, i) => {
    // Golden ratio distribution
    const phi = Math.acos(1 - (2 * (i + 0.5)) / count);
    const theta = Math.PI * (1 + Math.sqrt(5)) * (i + 0.5);

    const x = radius * Math.sin(phi) * Math.cos(theta);
    const y = radius * Math.sin(phi) * Math.sin(theta);
    const z = radius * Math.cos(phi);

    return {
      scheme,
      position: [x, y, z] as [number, number, number],
    };
  });

  useFrame((state) => {
    if (groupRef.current) {
      // Slow global rotation
      groupRef.current.rotation.y = state.clock.getElapsedTime() * 0.05;
    }
  });

  return (
    <group ref={groupRef}>
      {/* Central Star representation */}
      <mesh>
        <sphereGeometry args={[0.5, 32, 32]} />
        <meshBasicMaterial color="#cba6f7" />
      </mesh>
      
      {/* Core glow */}
      <pointLight position={[0, 0, 0]} intensity={1.5} distance={15} color="#cba6f7" />

      {nodes.map((node, idx) => (
        <SphereNode
          key={idx}
          scheme={node.scheme}
          position={node.position}
          onFundClick={onFundClick}
          isPrivate={isPrivate}
        />
      ))}
    </group>
  );
}

export default function AllocationSphere({ schemes, onFundClick, isPrivate }: AllocationSphereProps) {
  return (
    <div className="w-full h-[400px] md:h-[450px] relative overflow-hidden bg-[#181825]/20 border border-white/5 rounded-[2.5rem] shadow-inner select-none">
      <Canvas camera={{ position: [0, 0, 11], fov: 60 }}>
        <ambientLight intensity={0.6} />
        <directionalLight position={[10, 10, 10]} intensity={1.2} />
        <OrbitingGroup schemes={schemes} onFundClick={onFundClick} isPrivate={isPrivate} />
        <OrbitControls
          enableZoom={true}
          maxDistance={15}
          minDistance={5}
          enablePan={false}
          autoRotate={false}
        />
      </Canvas>
      <div className="absolute bottom-5 left-1/2 -translate-x-1/2 flex gap-3 text-[8px] font-black uppercase tracking-widest text-[#6c7086] pointer-events-none select-none bg-[#11111b]/50 border border-white/5 px-4 py-2 rounded-full backdrop-blur-sm">
        <span className="flex items-center gap-1.5"><span className="w-2 h-2 rounded-full bg-[#a6e3a1]" /> Bull</span>
        <span className="flex items-center gap-1.5"><span className="w-2 h-2 rounded-full bg-[#fab387]" /> Neutral</span>
        <span className="flex items-center gap-1.5"><span className="w-2 h-2 rounded-full bg-[#f38ba8]" /> Bear</span>
      </div>
    </div>
  );
}
