import Image from 'next/image'
import Link from 'next/link'
import { UserMenu } from './UserMenu'

export function Navbar() {
  return (
    <nav className="border-b bg-background">
      <div className="container mx-auto px-4 h-16 flex items-center justify-between">
        <Link href="/" className="flex items-center space-x-2">
          <Image 
            src="/icon.webp" 
            alt="Javasight Logo" 
            width={32} 
            height={32}
          />
          <span className="text-xl font-semibold">JAVASIGHT</span>
        </Link>
        <UserMenu />
      </div>
    </nav>
  )
} 